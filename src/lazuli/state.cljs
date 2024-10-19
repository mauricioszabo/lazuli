(ns lazuli.state)

(defonce connections (atom (sorted-map)))

(defn get-state [lang]
  (get @connections lang))
