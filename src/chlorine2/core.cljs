(ns chlorine2.core
  (:require [chlorine.ui.atom :as atom]
            [chlorine2.connections :refer [connect-nrepl!]]
            [chlorine.ui.inline-results :as inline]))

(def commands
  (fn []
   (clj->js {:connect #(connect-nrepl!)
             :clear-inline-results #(inline/clear-results! (atom/current-editor))})))
