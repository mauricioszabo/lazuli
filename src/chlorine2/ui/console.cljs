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

(defonce div (doto (js/document.createElement "div")
                   (aset "tabIndex" 1)
                   (.. -classList (add "native-key-bindings"))))

(defn open-console [split destroy-fn]
  (let [active (. js/document -activeElement)
        _ (aset console "destroy" destroy-fn)
        p (.. js/atom
              -workspace
              (open "atom://chlorine2-terminal" #js {:split split
                                                     :searchAllPanes true
                                                     :activatePane false
                                                     :activateItem false}))]
    (.then p #(.focus active))
    (.then p #(-> div .-childNodes first))))

(defn register-console! [^js subs]
  (let [scrolled? (atom true)]
        ; con (with-meta console/console-view
        ;       {:get-snapshot-before-update #(reset! scrolled? (console/all-scrolled?))
        ;        :component-did-update #(console/scroll-to-end! scrolled?)})]
    (.add subs
          (.. js/atom -workspace
              (addOpener (fn [uri]
                           (when (= uri "atom://chlorine2-terminal")
                             (prn :WILl-OPEN)
                             (aset div "innerHTML" "")
                             (.appendChild div (console/view))
                             console)))))
    (.add subs (.. js/atom -views (addViewProvider Console (constantly div))))))

(def registered (register-console! @aux/subscriptions))
