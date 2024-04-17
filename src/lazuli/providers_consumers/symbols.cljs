(ns lazuli.providers-consumers.symbols
  (:require [clojure.walk :as walk]
            ["path" :as path]
            [promesa.core :as p]))

(def find-symbol (atom nil))

(defn- get-symbols [meta]
  (when (and (-> meta :type (= "project-find")) @find-symbol)
    (p/then (@find-symbol)
            (fn [result]
              (when result
                (clj->js
                 [{:name ""
                   :type "project-find"
                   :file (-> result :definition/filename path/basename)
                   :directory (-> result :definition/filename path/dirname)
                   :position {:line (:definition/row result)
                              :column (:definition/column result 0)}}]))))))

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
                     (-> data js->clj walk/keywordize-keys get-symbols))})
