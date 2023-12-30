(ns chlorine2.core
  (:require [chlorine.ui.atom :as atom]
            [chlorine2.connections :refer [connect-nrepl!]]))

(def commands
  (fn []
    (clj->js {:connect #(connect-nrepl!)})))
