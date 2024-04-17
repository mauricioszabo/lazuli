(ns lazuli.core
  (:require [lazuli.ui.atom :as atom]
            [lazuli.connections :as conn]
            [lazuli.ui.inline-results :as inline]
            [tango.integration.interpreter :as int]
            ["fs" :as fs]
            ["path" :as path]))

(def config
  (clj->js
   {:console-pos {:type "string"
                  :title "Position of console when connecting REPL"
                  :enum ["right" "down"]
                  :default "right"}
    :eval-mode {:description "Should we evaluate Clojure or ClojureScript?"
                :type :string
                :enum [:prefer-clj :prefer-cljs :clj :cljs]
                :default :prefer-clj}}))

(defn- open-config! []
  (let [config (path/join (. js/atom getConfigDirPath) "lazuli" "config.cljs")]
    (when-not (fs/existsSync config)
      (try (fs/mkdirSync (path/dirname config)) (catch :default _))
      (fs/writeFileSync config (int/default-code 'lazuli.config)))
    (.. js/atom -workspace (open config))))

(def commands
  (fn []
   (clj->js {:connect conn/connect-nrepl!
             :open-config open-config!
             :clear-inline-results #(inline/clear-results! (atom/current-editor))})))

(defn deactivate [disconnect?]
  (when disconnect?
    (doseq [[_ state] @conn/connections
            :let [disconnect (-> @state :editor/commands :disconnect :command)]]
      (disconnect)))
  (.dispose ^js @atom/subscriptions))

(defonce ^:private old-connection (atom nil))

(defn- ^:dev/after-load before []
  (let [main (.. js/atom -packages (getActivePackage "lazuli") -mainModule)]
    (.activate main)
    #_
    (when-let [{:keys [host port]} (:repl/info @old-connection)]
      (conn/connect-nrepl! host port))
    (.. js/atom -notifications (addSuccess "Reloaded Lazuli"))
    (println "Reloaded")))

(defn- ^:dev/before-load-async after [done]
  (let [main (.. js/atom -packages (getActivePackage "lazuli") -mainModule)]
    #_
    (reset! old-connection (some-> @conn/connections
                                   vals
                                   first
                                   deref))
    ; (.deactivate main)
    (js/setTimeout done 500)))
