(ns lazuli.providers-consumers.autocomplete
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [promesa.core :as p]
            [saphire.code-treatment :as treat]
            [orbit.evaluation :as eval]
            [saphire.complete :as complete]
            [lazuli.state :as state]
            ["atom" :refer [Range]]))

#_
(.. js/atom -ui -fuzzyMatcher (setCandidates #js ["foo" "foa"]) (match "fo"))

; (defonce state (atom nil))

#_
(defn- min-word-size []
  (.. js/atom -config (get "autocomplete-plus.minimumWordLength")))

#_
(defn- re-escape [string]
  (str/replace string #"[\|\\\{\}\(\)\[\]\^\$\+\*\?\.\-\/]" "\\$&"))

#_
(defn- get-prefix [^js editor candidate]
  (let [non-word-chars (distinct (re-seq #"[^\w]" candidate))
        reg (re-pattern (str "[\\w" (re-escape (str/join "" non-word-chars)) "]+"))
        ^js cursor (-> editor .getCursors first)
        word-range (new Range
                     (.getBeginningOfCurrentWordBufferPosition cursor #js {:wordRegex reg})
                     (.getBufferPosition cursor))]
    (.getTextInRange editor word-range)))

(defn- treat-result [editor prefix {:keys [text/contents completion/type]}]
  (prn :WAT?)
  (let [[icon-name multiplier] (case type
                                 :local ["location status-modified" 1]
                                 :method/public ["organization status-renamed" 0.9]
                                 :method/protected ["person status-modified" 0.8]
                                 :method/private ["lock status-removed" 0.5]
                                 :property ["mention status-renamed" 0.9]
                                 :constant ["package status-renamed" 0.6]
                                 :keyword ["tag-add status-renamed" 0.2]
                                 ["question" 0.1])
        filter (str/replace prefix #"^:" "")
        match (.. js/atom -ui -fuzzyMatcher
                  (match (str/replace contents #"^:" "") filter #js {:recordMatchIndexes true}))]

    ; (prn :C contents)
    ; (prn :SUG prefix contents (.. js/atom -ui -fuzzyMatcher (score contents filter)))
    (when-let [score (some-> match .-score)]
      #js {:text contents
           :score (* multiplier score)
           :characterMatchIndices (.-matchIndexes match)
           :iconHTML (str "<i class='icon-" icon-name "'></i>")
           :replacementPrefix prefix})))

#_
(try
  (->> completions
               :completions/all
               ; (#(doto % (prn :FIRST)))
               (map #(treat-result editor (:completions/prefix completions) %))
               (#(doto % (prn :SECOND)))
               (sort-by #(- (.-score ^js %)))
               (#(doto % (prn :THIRD)))
               into-array)
  (catch :default e
    (prn :E e)))

(defn suggestions [{:keys [^js editor activatedManually]}]
  (p/let [state (-> editor .getGrammar .-name str/lower-case keyword state/get-state)
          eql (-> @state :editor/features :eql)
          completions (eql [{:editor/contents [:completions/all :completions/prefix]}])
          completions (:editor/contents completions)]
    (->> completions
         :completions/all
         (map #(treat-result editor (:completions/prefix completions) %))
         (filter identity)
         (sort-by #(- (.-score ^js %)))
         into-array)))

(defn- detailed-suggestion [suggestion])

(def provider
  (fn []
    #js {:selector ".source.ruby"
         :disableForSelector ".source.ruby .comment"

         :inclusionPriority 100
         :excludeLowerPriority false

         :suggestionPriority 200

         :filterSuggestions false

         :getSuggestions (fn [data]
                           (-> data js->clj walk/keywordize-keys suggestions))

         :getSuggestionDetailsOnSelect #(detailed-suggestion %)}))
