(ns chlorine.state
  (:require [reagent.core :as r]))

(def configs {:eval-mode
              {:description "Should we evaluate Clojure or ClojureScript?"
               :type [:prefer-clj :prefer-cljs :clj :cljs]
               :default :prefer-clj}

              :refresh-mode
              {:description "Should we use clojure.tools.namespace to refresh, or a simple require?"
               :type [:full :simple]
               :default :simple}

              :refresh-on-save
              {:description "Should we refresh namespaces when we save a file (Clojure Only)?"
               :type :boolean
               :default false}

              :experimental-features
              {:description "Enable experimental (and possibly unstable) features?"
               :type :boolean
               :default false}})

(defn seed-configs []
  (->> configs
       (map (fn [[k v]] [k (:default v)]))
       (into {})))

(defn get-config []
  (->> configs
       (map (fn [[k v]] [k (:default v)]))
       (into {})))

(defonce state
  (r/atom {:repls {:clj-eval nil
                   :cljs-eval nil
                   :clj-aux nil}
           :refresh {:needs-clear? true}
           :config (seed-configs)}))

#_
(let [eql (-> @state :tooling-state deref
              :editor/features
              :eql)]
 gensym
 (promesa.core/let [info (eql [:text/current-var
                                                             :definition/row :definition/col
                                                              :definition/filename
                                                              {:definition/contents [{:text/top-block [:text/contents]}]}])]
        (if-let [contents (:definition/contents info)]
          (->> contents
               (def res1))
          (promesa.core/let [pos [(:definition/row info) (:definition/col info)]
                             info (eql {:file/filename (:definition/filename info)}
                                       [{(list :file/contents {:range [pos pos]})
                                         [{:text/top-block [:text/contents]}]}])]
            (->> info
                 (def res2))))))
