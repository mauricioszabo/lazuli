(ns lazuli.providers-consumers.autocomplete
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [promesa.core :as p]
            [saphire.code-treatment :as treat]
            [orbit.evaluation :as eval]
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
                    "question")]
    {:text suggestion
     :iconHTML (str "<i class='icon-" icon-name "'></i>")
     :replacementPrefix (get-prefix editor suggestion)}))

(defn suggestions [{:keys [editor activatedManually]}]
  (p/let [dissected (treat/dissect-editor-contents @state)
          watch-id (treat/watch-id-for-code @state)
          res (treat/eql @state [:repl/evaluator
                                 {:editor/contents [:editor/filename :text/range]}])
          evaluator (:repl/evaluator res)
          editor-info (:editor/contents res)
          method-code (str "binding.local_variables.map { |m| ['local_var', m.to_s] } + "
                           "__self__.public_methods.map { |m| ['pub_method', m.to_s] } + "
                           "__self__.private_methods.map { |m| ['priv_method', m.to_s] } + "
                           "__self__.protected_methods.map { |m| ['prot_method', m.to_s] }")
          code (case (:type dissected)
                 "constant" "::Object.constants.map { |i| ['constant', i.to_s] }"
                 "instance_variable" "instance_variables.map { |i| ['instance_var', i.to_s] }"
                 "class_variable" "class_variables.map { |i| ['class_var', i.to_s] }"
                 "identifier" (str/replace method-code #"__self__\." "")
                 "scope_resolution" (str (:callee dissected) ".constants.map { |i| ['constant', i.to_s] }")
                 "call" (if (-> dissected :sep (= "::"))
                          (str (:callee dissected) ".constants.map { |i| ['constant', i.to_s] }")
                          (str/replace method-code #"__self__" (:callee dissected))))]

    (p/catch
     (p/let [res (try (eval/evaluate evaluator
                                     (cond-> {:code code
                                              :file (-> editor-info :editor/filename)
                                              :line (-> editor-info :text/range ffirst)}
                                       watch-id (assoc :watch_id watch-id))
                                     {:plain true :options {:op "eval"}})
                   (catch :default _))]
       (clj->js (map #(treat-result editor %) res)))
     (constantly nil))))

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
