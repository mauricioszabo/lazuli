(ns chlorine.providers-consumers.autocomplete
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [promesa.core :as p]))

(defonce tango-complete (atom nil))

(def clj-var-regex #"[a-zA-Z0-9\-.$!?\/><*=\?_:]+")

(defn- min-word-size []
  (.. js/atom -config (get "autocomplete-plus.minimumWordLength")))

(defn- treat-result [prefix {:keys [completion/type text/contents]}]
  {:text contents
   :type type
   :replacementPrefix prefix})

(defn suggestions [{:keys [^js editor] :as s}]
  (when-let [complete @tango-complete]
    (p/let [completions (complete)
            buffer (.getBuffer editor)
            current-word (.. editor
                             getLastCursor
                             (getCurrentWordBufferRange #js {:wordRegex clj-var-regex}))
            current-pos (.getCursorBufferPosition editor)
            prefix (.getTextInBufferRange editor #js [(.-start current-word) current-pos])]
      (->> completions
           (map (partial treat-result prefix))
           clj->js))))

#_
(defn- meta-for-var [var]
  (p/let [state (:tooling-state @state)
          res (cmds/run-feature! state :eval {:text (str "(meta (resolve '" var "))")
                                              :auto-detect true
                                              :aux true})]
    (:result res)))

(defn- detailed-suggestion [suggestion]
  #_
  (p/catch
   (p/let [txt (.-text suggestion)
           {:keys [arglists doc]} (meta-for-var txt)]
     (aset suggestion "rightLabel" (str arglists))
     (aset suggestion "description" (-> doc str (str/replace #"(\n\s+)" " ")))
     suggestion)
   (constantly nil)))

(def provider
  (fn []
    #js {:selector ".source.clojure"
         :disableForSelector ".source.clojure .comment"

         :inclusionPriority 100
         :excludeLowerPriority false

         :suggestionPriority 200

         :filterSuggestions true

         :getSuggestions (fn [data]
                           (-> data js->clj walk/keywordize-keys suggestions clj->js))

         :getSuggestionDetailsOnSelect #(detailed-suggestion %)}))
