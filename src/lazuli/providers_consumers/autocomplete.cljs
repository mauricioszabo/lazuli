(ns lazuli.providers-consumers.autocomplete
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [promesa.core :as p]
            [saphire.code-treatment :as treat]
            [orbit.evaluation :as eval]
            [saphire.complete :as complete]
            ["atom" :refer [Range]]))

(defonce state (atom nil))

(defn- min-word-size []
  (.. js/atom -config (get "autocomplete-plus.minimumWordLength")))

(defn- re-escape [string]
  (str/replace string #"[\|\\\{\}\(\)\[\]\^\$\+\*\?\.\-\/]" "\\$&"))

(defn- get-prefix [^js editor candidate]
  (let [non-word-chars (distinct (re-seq #"[^\w]" candidate))
        reg (re-pattern (str "[\\w" (re-escape (str/join "" non-word-chars)) "]+"))
        ^js cursor (-> editor .getCursors first)
        word-range (new Range
                     (.getBeginningOfCurrentWordBufferPosition cursor #js {:wordRegex reg})
                     (.getBufferPosition cursor))]
    (.getTextInRange editor word-range)))

(defn- treat-result [editor [kind suggestion]]
  (let [icon-name (case kind
                    "local_var" "chevron-right status-modified"
                    "pub_method" "eye status-renamed"
                    "priv_method" "stop status-removed"
                    "prot_method" "lock status-modified"
                    "instance_var" "mention status-renamed"
                    "class_var" "mention status-renamed"
                    "constant" "package status-renamed"
                    "symbol" "info status-renamed"
                    "question")]
    {:text suggestion
     :iconHTML (str "<i class='icon-" icon-name "'></i>")
     :replacementPrefix (get-prefix editor suggestion)}))

(defn suggestions [{:keys [editor activatedManually]}]
  (p/let [res (complete/suggestions @state)]
    (->> res
         (map #(treat-result editor %))
         clj->js)))

(defn- detailed-suggestion [suggestion])

(def provider
  (fn []
    #js {:selector ".source.ruby"
         :disableForSelector ".source.ruby .comment"

         :inclusionPriority 100
         :excludeLowerPriority false

         :suggestionPriority 200

         :filterSuggestions true

         :getSuggestions (fn [data]
                           (-> data js->clj walk/keywordize-keys suggestions))

         :getSuggestionDetailsOnSelect #(detailed-suggestion %)}))
