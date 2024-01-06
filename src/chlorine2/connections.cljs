(ns chlorine2.connections
  (:require [chlorine.utils :as aux]
            [reagent.dom :as rdom]
            [tango.editor-helpers :as helpers]
            [tango.integration.connection :as conn]
            [reagent.core :as r]
            [tango.ui.edn :as edn]
            [chlorine.state :as state]
            [chlorine.ui.atom :as atom]
            [chlorine.ui.inline-results :as inline]
            [chlorine2.ui.console :as console]
            [tango.ui.console :as tango-console]
            [promesa.core :as p]))

(defonce ^:private connections
  (atom (sorted-map)))

(defn destroy! [^js panel]
  (.destroy panel)
  (aux/refocus!))

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
    (aux/save-focus! div)
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
                                              (str "chlorine-pulsar:" command-name)
                                              (fn [] (command-function))))]
    (swap! commands conj disposable)))

(defn- register-commands! [console commands]
  (remove-all-commands!)
  (add-command! "clear-console" #(tango-console/clear @console))
  (doseq [[key {:keys [command]}] commands]
    (add-command! (name key) command)))

(defn- diagnostic! [conn-id console output]
  (let [connection (get @connections conn-id)
        parse (-> @connection :editor/features :result-for-renderer)
        append-error (fn [error]
                       (let [div (doto (js/document.createElement "div")
                                       (.. -classList (add "content")))
                             hiccup (parse {:result error})]
                         (rdom/render hiccup div)
                         (tango-console/append console div ["icon-bug"])))]
    (cond
      (-> output meta :orbit.shadow/error)
      (append-error output)

      (:orbit.shadow/compile-info output)
      (tango-console/update-build-status console (:orbit.shadow/compile-info output))

      (:orbit.shadow/clients-changed output)
      (tango-console/update-clients console
                                    connection
                                    (:orbit.shadow/clients-changed output)))))

(defn- start-eval! [console result]
  (inline/create! result)

  (let [div (js/document.createElement "div")]
    (set! (.-id div) (:id result))
    (.. div -classList (add "content" "pending"))
    (set! (.-innerHTML div) "<span class='tango icon loading'></span>")
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
        (rdom/render hiccup div)))))

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
  (let [position (clj->js (cond-> {:initialLine line :searchAllPanes true}
                                  column (assoc :initialColumn column)))]
    (if contents
      (open-ro-editor file-name line column position contents)
      (.. js/atom -workspace (open file-name position)))))

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
                      :on-stdout #(tango-console/stdout @console %)
                      :on-stderr #(tango-console/stderr @console %)
                      :on-diagnostic #(diagnostic! id @console %)
                      :on-start-eval #(start-eval! @console %)
                      :on-eval #(did-eval! @console id %)
                      :notify notify!
                      :open-editor open-editor
                      :register-commands #(register-commands! console %)
                      :prompt (partial prn :PROMPT)
                      :get-config #(state/get-config)
                      :editor-data #(get-editor-data)}
           repl-state (conn/connect! host port callbacks)]
     (when repl-state
       (p/then (console/open-console (.. js/atom -config (get "chlorine.console-pos"))
                                     #((-> @repl-state :editor/commands :disconnect :command)))
               (fn [c]
                 (tango-console/clear c)
                 (reset! console c)))
       (swap! connections assoc id repl-state)))))
