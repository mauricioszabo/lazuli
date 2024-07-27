(ns lazuli.connections
  (:require [reagent.dom :as rdom]
            [clojure.string :as str]
            [tango.editor-helpers :as helpers]
            [orbit.evaluation :as eval]
            [tango.integration.connection :as conn]
            [reagent.core :as r]
            [tango.ui.edn :as edn]
            [lazuli.ui.atom :as atom]
            [lazuli.ui.inline-results :as inline]
            [lazuli.ui.console :as console]
            [tango.ui.console :as tango-console]
            [lazuli.providers-consumers.lsp :as lsp]
            [promesa.core :as p]
            [tango.commands-to-repl.pathom :as pathom]
            [lazuli.providers-consumers.autocomplete :as lazuli-complete]
            [lazuli.providers-consumers.symbols :as symbols]
            [saphire.code-treatment :as treat]
            [saphire.ui.interface :as interface]
            [lazuli.ruby-parsing :as rp]
            [com.wsscode.pathom3.connect.operation :as connect]
            [tango.ui.elements :as ui]
            [saphire.connections :as s-connections]
            ["path" :as path]
            ["fs" :as fs]))

(defonce connections (atom (sorted-map)))

(defn destroy! [^js panel]
  (.destroy panel)
  (atom/refocus!))

(defn- treat-key [cmd panel event]
  (case (.-key event)
    "Escape" (destroy! panel)
    "Enter" (cmd panel)
    :no-op))

(defn- as-clj [nodelist]
  (js->clj (.. js/Array -prototype -slice (call nodelist))))

(def ^:private local-state (r/atom {:hostname "localhost"}))

(defn- set-port-from-file! []
  (let [paths (into [] (-> js/atom .-project .getPaths (or ["."])))
        port (helpers/get-possible-port paths (:typed-port @local-state))]
    (when port
      (swap! local-state assoc :port port))))

(defn view []
  [:div.native-key-bindings.tab-able
   [:h2 "Connect to nREPL"]
   [:div.block
    [:label "Host: "]
    [:input.input-text {:type "text"
                        :tab-index "1"
                        :value (:hostname @local-state)
                        :on-change #(swap! local-state assoc :hostname (-> % .-target .-value))
                        :on-focus #(-> % .-target .select)}]]
   [:div.block
    [:label "Port: "]
    [:input.input-text {:type "text"
                        :tab-index "2"
                        :placeholder "port"
                        :value (:port @local-state)
                        :on-change #(swap! local-state assoc
                                           :port (-> % .-target .-value int)
                                           :typed-port (-> % .-target .-value int))
                        :on-focus #(-> % .-target .select)}]]])

(defn conn-view [cmd]
  (let [div (. js/document (createElement "div"))
        panel (.. js/atom -workspace (addModalPanel #js {:item div}))]
    (set-port-from-file!)
    (rdom/render [view] div)
    (atom/save-focus! div)
    (doseq [elem (-> div (.querySelectorAll "input") as-clj)]
      (aset elem "onkeydown" (partial treat-key cmd panel)))))

(defn- get-editor-data []
  (when-let [^js editor (atom/current-editor)]
    (let [range (.getSelectedBufferRange editor)
          start (.-start range)
          end (.-end range)]
      {:editor editor
       :contents (.getText editor)
       :filename (.getPath editor)
       :range [[(.-row start) (.-column start)]
               [(.-row end) (cond-> (.-column end)
                              (not= (.-column start) (.-column end)) dec)]]})))

(defn- notify! [{:keys [type title message]}]
  (case type
    :info (atom/info title message)
    :warn (atom/warn title message)
    (atom/error title message)))

(defonce ^:private commands (atom []))
(defn- remove-all-commands! []
  (doseq [^js disposable @commands]
    (.dispose disposable))
  (reset! commands []))

(defn disconnect! [id]
  (when (get @connections id)
    (atom/info "Disconnected" "Disconnected from REPL"))
  (swap! connections dissoc id)
  (remove-all-commands!))

(defn- add-command! [command-name command-function]
  (let [disposable (.. js/atom -commands (add "atom-text-editor"
                                              (str "lazuli:" command-name)
                                              (fn [] (command-function))))]
    (swap! commands conj disposable)))

(defn- observe-editor! [state ^js editor]
  (when editor
    (when-let [display! (-> @state :editor/features :display-watches)]
      (when-let [path (not-empty (.getPath editor))]
        (display! path)))))

(defn- stop-changing! [state ^js editor ^js changes]
  (when-let [path (.getPath editor)]
    (let [update! (-> @state :editor/features :update-watches)
          render-watches! (-> @state :editor/features :render-watches)]
      (doseq [^js change (.-changes changes)
              :let [delta (- (.. change -newExtent -row) (.. change -oldExtent -row))]
              :when (not= 0 delta)]
        (update! path (.. change -oldStart -row) delta)))))

(defn- observe-editors! [state, ^js editor]
  (swap! commands conj (.onDidStopChanging editor #(stop-changing! state editor %))))

(defn- register-commands! [console cmds state]
  (def state state)
  (remove-all-commands!)
  (swap! commands conj (.. js/atom -workspace (observeActiveTextEditor #(observe-editor! state %))))
  (swap! commands conj (.. js/atom -workspace (observeTextEditors #(observe-editors! state %))))
  (doseq [[key {:keys [command]}] cmds]
    (add-command! (name key) command)))

(defn- start-eval! [console result]
  (inline/create! result)

  (let [div (js/document.createElement "div")]
    (set! (.-id div) (:id result))
    (.. div -classList (add "content" "pending"))
    (set! (.-innerHTML div) "<div class='tango icon-container'><span class='icon loading'></span></div>")
    (tango-console/append console div ["icon-code"])
    (js/setTimeout (fn []
                     (when (.. div -classList (contains "pending"))
                       (.. div -classList (remove "pending"))
                       (set! (.-innerHTML div)
                         "<div class='result error'>Timed out while waiting for a result</div>")))
                   30000)))

(defn- did-eval! [console conn-id result]
  (let [connection (get @connections conn-id)]
    (inline/update! connection result)

    (when-let [div (.querySelector console (str "#" (:id result)))]
      (.. div -classList (remove "pending"))
      (let [parse (-> @connection :editor/features :result-for-renderer)
            hiccup (parse result connection)]
        (set! (.-innerHTML div) "")
        (.appendChild div (ui/dom hiccup))))))

(defn- open-ro-editor [file-name line col position contents]
  (.. js/atom
      -workspace
      (open file-name position)
      (then #(doto ^js %
                   (aset "isModified" (constantly false))
                   (aset "save" (fn [ & _] (atom/warn "Can't save readonly editor" "")))
                   (.setText contents)
                   (.setReadOnly true)
                   (.setCursorBufferPosition #js [line (or col 0)])))))

(defn- open-editor [{:keys [file-name line contents column]}]
  (let [position (clj->js (cond-> {:initialLine line :searchAllPanes true :location "center" :split "left"}
                                  column (assoc :initialColumn column)))]
    (if contents
      (open-ro-editor file-name line column position contents)
      (.. js/atom -workspace (open file-name position)))))

(defn- get-config []
  (let [config (.. js/atom -config (get "lazuli"))]
    {:max-traces (-> config (aget "number-of-traces"))
     :project-paths (into [] (.. js/atom -project getPaths))
     ;; Compatibility with Duck-REPLed
     :eval-mode :clj
     :console-pos (-> config (aget "console-pos") keyword)}))

(defn- open-console! [repl-state]
  (console/open-console (.. js/atom -config (get "lazuli.console-pos"))
                        #((-> @repl-state :editor/commands :disconnect :command))))

(defn connect-nrepl!
  ([]
   (conn-view (fn [panel]
                (connect-nrepl! (:hostname @local-state)
                                (:port @local-state))
                (destroy! panel))))
  ([host port]
   (p/let [id (-> @connections keys last inc)
           console (atom nil)
           callbacks {:on-disconnect #(disconnect! id)
                      ;; FIXME - move everything that uses @console to a different callback?
                      :on-start-eval #(start-eval! @console %)
                      :on-eval #(did-eval! @console id %)
                      :register-commands register-commands!
                      :get-rendered-results #(concat (inline/all-parsed-results)
                                                     (tango-console/all-parsed-results @console))
                      :notify notify!
                      :open-editor open-editor
                      :prompt (partial prn :PROMPT)
                      :get-config #(get-config)
                      :editor-data #(get-editor-data)
                      :config-directory (path/join (. js/atom getConfigDirPath) "lazuli")}
           repl-state (s-connections/connect-nrepl! host port callbacks
                                                    {:open-console open-console!
                                                     :set-console? true})]
     (when repl-state
       (reset! console ((-> @repl-state :editor/callbacks :get-console)))
       (reset! lazuli-complete/state repl-state)
       (reset! symbols/find-symbol (-> @repl-state :editor/features :find-definition))
       (swap! connections assoc id repl-state)))))
