(ns chlorine.providers-consumers.symbols
  (:require [clojure.walk :as walk]))

(def tango-goto-symbol (atom nil))

(defn- get-symbols [meta]
  (prn :META meta)
  (def m meta)
  [])

(defn- can-provide? []
  true)

(defn provider []
  (prn :PROVIDE!!!)
  #js {:packageName "Chlorine"
       :name "Chlorine"
       :canProvideSymbols #(can-provide?) ;
       ; :inclusionPriority 100
       ; :excludeLowerPriority false
       ;
       ; :suggestionPriority 200
       ;
       ; :filterSuggestions true

       :getSymbols (fn [data]
                     (-> data js->clj walk/keywordize-keys get-symbols clj->js))})
