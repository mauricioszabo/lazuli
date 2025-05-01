(ns lazuli.ui.console
  (:require [lazuli.ui.atom :as atom]
            [tango.ui.console :as console]))

(defonce ^:private console-pair
  (do
    (deftype ^js ConsoleClass []
      Object
      (getTitle [_] "Lazuli REPL")
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
              (open "atom://lazuli-terminal" #js {:split split
                                                  :searchAllPanes true
                                                  :activatePane false
                                                  :activateItem false}))]
    (.then p #(.focus active))
    (.then p #(-> div .-childNodes first))))

(defn register-console! [^js subs]
  (.add subs
        (.. js/atom -workspace
            (addOpener (fn [uri]
                         (when (= uri "atom://lazuli-terminal")
                           (aset div "innerHTML" "")
                           (.appendChild div (console/view "lazuli"))
                           console)))))
  (.add subs (.. js/atom -views (addViewProvider Console (constantly div)))))

(def registered (register-console! @atom/subscriptions))
