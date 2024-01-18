(ns chlorine2.core
  (:require [chlorine.ui.atom :as atom]
            [chlorine2.connections :as conn]
            [chlorine.ui.inline-results :as inline]
            [chlorine.utils :as aux]
            [tango.integration.interpreter :as int]
            ["fs" :as fs]
            ["path" :as path]))

(defn- open-config! []
  (let [config (path/join (. js/atom getConfigDirPath) "chlorine" "config.cljs")]
    (when-not (fs/existsSync config)
      (try (fs/mkdirSync (path/dirname config)) (catch :default _))
      (fs/writeFileSync config (int/default-code 'chlorine.config)))
    (.. js/atom -workspace (open config))))

(def commands
  (fn []
   (clj->js {:connect conn/connect-nrepl!
             :open-config open-config!
             :clear-inline-results #(inline/clear-results! (atom/current-editor))})))

(defn deactivate []
  (doseq [[_ state] @conn/connections
          :let [disconnect (-> @state :editor/commands :disconnect :command)]]
    (disconnect))
  (.dispose ^js @aux/subscriptions))

(defonce ^:private old-connection (atom nil))

(defn- ^:dev/after-load before []
  (let [main (.. js/atom -packages (getActivePackage "chlorine") -mainModule)]
    (.activate main)
    (when-let [{:keys [host port]} (:repl/info @old-connection)]
      (conn/connect-nrepl! host port))
    (.. js/atom -notifications (addSuccess "Reloaded Chlorine"))
    (println "Reloaded")))

(defn- ^:dev/before-load-async after [done]
  (let [main (.. js/atom -packages (getActivePackage "chlorine") -mainModule)]
    (reset! old-connection (some-> @conn/connections
                                   vals
                                   first
                                   deref))
    (.deactivate main)
    (js/setTimeout done 500)))
