(ns lazuli.providers-consumers.autocomplete
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [promesa.core :as p]
            [saphire.code-treatment :as treat]
            [orbit.evaluation :as eval]
            [saphire.complete :as complete]
            [tango.state :as state]
            ["atom" :refer [Range]]))

(defn- treat-result [editor prefix {:keys [text/contents completion/type]}]
  (let [[icon-name multiplier] (case type
                                 :enum ["book status-modified" 1]
                                 :function ["package status-renamed" 1]
                                 :keyword ["tag-add status-renamed" 1]
                                 :macro ["circuit-board status-renamed" 1]
                                 :namespace ["code status-renamed" 1]
                                 :var ["package status-renamed" 1]
                                 :special-form ["key status-modified" 0.7]
                                 :local ["location status-modified" 1]
                                 :method/public ["package status-renamed" 0.9]
                                 :method/protected ["person status-modified" 0.8]
                                 :method/private ["lock status-removed" 0.5]
                                 :property ["mention status-renamed" 0.9]
                                 :constant ["package status-renamed" 0.6]
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
    #js {:selector ".source.ruby,.source.clojure"
         :disableForSelector ".source.ruby .comment"

         :inclusionPriority 100
         :excludeLowerPriority false

         :suggestionPriority 200

         :filterSuggestions false

         :getSuggestions (fn [data]
                           (-> data js->clj walk/keywordize-keys suggestions))

         :getSuggestionDetailsOnSelect #(detailed-suggestion %)}))
