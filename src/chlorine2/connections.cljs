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

(defn connect-nrepl!
  ([]
   (conn-view (fn [panel]
                (connect-nrepl! (:hostname @local-state)
                                (:port @local-state))
                (destroy! panel))))
  ([host port]
   (p/let [id (-> @connections keys last inc)
           console-state (r/atom [])
           callbacks {:on-disconnect #(disconnect! id)
                      :on-stdout #(swap! console-state conj [:stdout %])
                      :on-start-eval inline/create!
                      :on-eval #(inline/update! (get @connections id) %)
                      :notify notify!
                      :register-commands register-commands!
                      :prompt (partial prn :PROMPT)
                      :get-config #(state/get-config)
                      :on-stderr #(swap! console-state conj [:stderr %])
                      :editor-data #(get-editor-data)}
           repl (conn/connect! host port callbacks)]
     (swap! connections assoc id repl))))
