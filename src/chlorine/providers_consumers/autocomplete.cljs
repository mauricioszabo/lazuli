(ns chlorine.providers-consumers.autocomplete
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [chlorine.state :refer [state]]
            [promesa.core :as p]
            [repl-tooling.editor-integration.doc :as doc]
            [repl-tooling.eval :as eval]
            [repl-tooling.editor-integration.commands :as cmds]))

(def tango-complete (atom nil))

(def clj-var-regex #"[a-zA-Z0-9\-.$!?\/><*=\?_:]+")

(defn- min-word-size []
  (.. js/atom -config (get "autocomplete-plus.minimumWordLength")))

(defn- treat-result [re-prefix {:keys [completion/type text/contents]}]
  (let [prefix (re-find re-prefix contents)]
    {:text contents
     :type type
     :replacementPrefix prefix}))

(def ^:private re-char-escapes
  (->> "\\.*+|?()[]{}$^"
       set
       (map (juxt identity #(str "\\" %)))
       (into {})))

(defn- re-escape [prefix]
  (str/escape (str prefix) re-char-escapes))

(defn suggestions [{:keys [^js editor prefix] :as s}]
  (p/let [completions (@tango-complete)
          re-prefix (re-pattern (str ".*" (re-escape prefix)))]
    (->> completions
         (map (partial treat-result re-prefix))
         clj->js)))

(defn- meta-for-var [var]
  (p/let [state (:tooling-state @state)
          res (cmds/run-feature! state :eval {:text (str "(meta (resolve '" var "))")
                                              :auto-detect true
                                              :aux true})]
    (:result res)))

(defn- detailed-suggestion [suggestion]
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
