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

(defn- register-commands! [commands]
  (remove-all-commands!)
  (doseq [[key {:keys [command]}] commands]
    (add-command! (name key) command)))

(defn- text-with-stacktrace [texts-or-traces]
  ^:tango/interactive
  {:html
    (into [:div.rows]
          (for [row texts-or-traces]
            (if (map? row)
              [:div.children
               [:div.cols
                "at "
                [:a {:href "#" :on-click '(fn [e]
                                            (.stopPropagation e)
                                            (.preventDefault e)
                                            (prn "LOL!"))}
                 (str (:file row) ":" (:row row) (when-let [col (:col row)]
                                                   (str ":" col)))]]]
              row)))})

(defn- shadow-error [error]
  (let [traces (re-seq #" File: (.*?):(\d+):(\d+)" error)]
    (->> traces
         (map (fn [[_ file row col?]]
                (cond-> {:file file :row row}
                  col? (assoc :col col?))))
         (into [[:div.title "Errors found in compilation process"]
                [:div.space]
                [:div error]])
         text-with-stacktrace)))

(defn- diagnostic! [conn-id console-state output]
  (let [connection (get @connections conn-id)
        parse (-> @connection :editor/features :result-for-renderer)]
    (def parse parse)
    (cond
      (:orbit.shadow/errors output)
      (swap! console-state update :outputs conj ^:icon-bug [:div.content (parse {:result (shadow-error (:orbit.shadow/errors output))})])))
      ; (swap! console-state update :outputs conj
      ;        ^:icon-bug (parse {:result (shadow-error (:orbit.shadow/errors output))}))))

 ; (nil 10)
 (tap> [:diag output]))


; (shadow-error "FOO")
; (:outputs @console-state)
; ; (nil 10)

(defn- start-eval! [console-state result]
  (inline/create! result)
  (let [r (r/atom [:div [:span {:class "repl-tooling icon loading"}]])]
    (swap! console-state
           #(-> %
                (assoc-in [:evals (:id result)] r)
                (update :outputs conj ^:icon-code [:div.content [(fn [] @r)]])))))

(defn- did-eval! [console-state conn-id result]
  (let [connection (get @connections conn-id)
        parse (-> @connection :editor/features :result-for-renderer)]
    (inline/update! connection result)
    (when-let [res (get-in @console-state [:evals (:id result)])]
      (reset! res (parse result))
      (swap! console-state update :evals dissoc (:id result)))))

(defn connect-nrepl!
  ([]
   (conn-view (fn [panel]
                (connect-nrepl! (:hostname @local-state)
                                (:port @local-state))
                (destroy! panel))))
  ([host port]
   (p/let [id (-> @connections keys last inc)
           console-state (r/atom {:outputs [] :evals {}})
           _ (def console-state console-state)
           out-state (r/cursor console-state [:outputs])
           callbacks {:on-disconnect #(disconnect! id)
                      :on-stdout #(tango-console/append-text out-state :stdout %)
                      :on-stderr #(tango-console/append-text out-state :stderr %)
                      :on-diagnostic #(diagnostic! id console-state %)
                      :on-start-eval #(start-eval! console-state %)
                      :on-eval #(did-eval! console-state id %)
                      :notify notify!
                      :register-commands register-commands!
                      :prompt (partial prn :PROMPT)
                      :get-config #(state/get-config)
                      :editor-data #(get-editor-data)}
           repl-state (conn/connect! host port callbacks)]
     (console/open-console out-state
                           (.. js/atom -config (get "chlorine.console-pos"))
                           #((-> @repl-state :editor/commands :disconnect :command)))
     (swap! connections assoc id repl-state))))

; ^:tango/interactive {:html [:div.title "Hello"]}
; (ex-info "LOL" {})
; (-> (re-seq #" File: (.*?):(\d+):(\d+)" (:orbit.shadow/errors o)))
; (println (:orbit.shadow/errors o))
; (nil 109)
; (nil 300)

; (/ 10 0)

; (connect-nrepl! "10")

{:foo [1 2 3]}
