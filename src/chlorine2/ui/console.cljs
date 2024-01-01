(ns chlorine2.ui.console
  (:require [reagent.dom :as rdom]
            [tango.ui.console :as console]
            [chlorine.utils :as aux]))

(defonce ^:private console-pair
  (do
    (deftype ^js ConsoleClass []
      Object
      (getTitle [_] "Chlorine REPL")
      (destroy [this]
        (-> (filter #(.. ^js % getItems (includes this))
                    (.. js/atom -workspace getPanes))
            first
            (some-> (.removeItem this)))))
    [ConsoleClass  (ConsoleClass.)]))
(def ^:private Console (first console-pair))
(def ^:private console (second console-pair))

(defn open-console [state split destroy-fn]
  (let [active (. js/document -activeElement)]
    (aset console "destroy" destroy-fn)
    (.. js/atom
        -workspace
        (open "atom://chlorine2-terminal" #js {:split split
                                               :searchAllPanes true
                                               :activatePane false
                                               :state state
                                               :activateItem false})
        (then #(.focus active)))))

(defn register-console! [^js subs]
  (let [scrolled? (atom true)
        con (with-meta console/console-view
              {:get-snapshot-before-update #(reset! scrolled? (console/all-scrolled?))
               :component-did-update #(console/scroll-to-end! scrolled?)})]
    (.add subs
          (.. js/atom -workspace
              (addOpener (fn [uri options]
                           (when (= uri "atom://chlorine2-terminal")
                             (rdom/render [con (.-state options) "native-key-bindings"]
                                          console/div)
                             console)))))
    (.add subs (.. js/atom -views (addViewProvider Console (constantly console/div))))))

(defonce registered (register-console! @aux/subscriptions))
