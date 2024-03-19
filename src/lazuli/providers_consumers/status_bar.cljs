(ns lazuli.providers-consumers.status-bar
  (:require [reagent.dom :as rdom]))

(defonce status-bar (atom nil))
(defonce status-bar-tile (atom nil))

(defn- view []
  #_
  [:div
   (when (some-> @state :tooling-state deref :clj/repl)
     [:span
      " "
      [:img {:src (str "file://" js/__dirname "/clj.png") :width 18}]
      (cond-> " CLJ"
              (-> @state :config :refresh-mode (= :simple)) (str " (simple)")
              (-> @state :config :refresh-mode (not= :simple)) (str " (full)"))])

   (when (some-> @state :tooling-state deref :cljs/repl)
     [:span {:style {:margin-left "13px"}}
      [:img {:src (str "file://" js/__dirname "/cljs.png") :width 18}]
      " CLJS"])])

(defn activate [s]
  (swap! status-bar #(or % s))
  (let [div (. js/document (createElement "div"))]
    (.. div -classList (add "inline-block" "lazuli"))
    (reset! status-bar-tile (. ^js @status-bar
                              (addRightTile #js {:item div :priority 101})))
    (rdom/render [view] div)))
