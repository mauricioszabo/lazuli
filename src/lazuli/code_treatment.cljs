(ns lazuli.code-treatment
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [tango.commands-to-repl.pathom :as pathom]))

(defn- dissect-node [node]
  (let [myself (.-text node)
        my-type (.-type node)
        parent (.-parent node)
        parent-type (.-type parent)
        [callee sep caller params?] (.-children parent)
        my-children (delay (mapv #(.-text %) (.-children node)))
        callee-txt (.-text callee)
        assign (fn [element ^js val]
                 (if (#{"operator_assignment" "assignment"} (.-type val))
                   (let [[l a] (.-children val)]
                     (assoc element :assign (str (.-text l) " " (.-text a))))
                   element))
        params (fn [params]
                 (when-let [params (some-> params .-text)]
                   (if (str/starts-with? params "(")
                     params
                     (str " " params))))]

    (cond
      (#{"call" "scope_resolution"} my-type)
      (assign {:identifier ""
               :type my-type
               :sep (second @my-children)
               :callee (first @my-children)}
              parent)

      (= "scope_resolution" parent-type)
      (assign {:identifier (.-text caller)
               :type "scope_resolution"
               :sep (.-text sep)
               :callee callee-txt}
              (.-parent parent))

      (or (not= "call" parent-type) (= myself callee-txt))
      (assign {:identifier myself
               :type (.-type node)}
              parent)

      caller
      (assign {:identifier (.-text caller)
               :type "call"
               :params (params params?)
               :sep (.-text sep)
               :callee callee-txt}
              (.-parent parent))

      :simple-call
      (assign {:identifier (.-text callee)
               :type "call"
               :params (params sep)}
              (.-parent parent)))))

(defn- dissect-editor-contents
  ([state]
   (p/let [eql (-> @state :editor/features :eql)
           {:keys [editor/contents]} (eql [:editor/contents])]
     (dissect-editor-contents (:text/contents contents) (:text/range contents))))
  ([text range]
   (p/let [[[r-start c-start] [r-end c-end]] range
           ^js parsed (.parse pathom/parser text)
           ^js q pathom/any-query
           captures (.captures q (.-rootNode parsed)
                               #js {:row r-start :column (dec c-start)}
                               #js {:row r-end :column c-end})
           dissected (-> captures last .-node dissect-node)]
     (.delete parsed)
     dissected)))

(defn- translate-captures-into-method [captures-res]
  (let [[parents [capture]] (split-with #(= "parent" (.-name %)) captures-res)]
    (when capture
      (let [^js node (.-node capture)
            texts (map (fn [parent]
                         (let [[kind name] (.. parent -node -children)]
                           (str (.-text kind) " " (.-text name))))
                       parents)
            parent-name (str/replace (str/join "::" texts) #"(class|module) " "")
            original-row (-> node .-startPosition .-row)
            is-method? (-> capture .-name (= "method"))
            method-part (-> node .-children (nth 1) .-text)]
        {:row original-row
         :method (str parent-name
                      (if is-method? "#" ".")
                      method-part)}))))

(defn method-name [source row]
  (let [parsed (.parse pathom/parser source)
        captures-res (. pathom/parent-query captures (.-rootNode parsed) #js {:row row :column 0})
        result (when (seq captures-res) (translate-captures-into-method captures-res))]
    (.delete parsed)
    result))

(defn eql [state query]
   (p/let [eql (-> @state :editor/features :eql)
           result (eql (if (keyword? query) [query] query))]
     (if (keyword? query)
       (query result)
       result)))

(defn watch-id-for-code
  ([state]
   (p/let [eql (-> @state :editor/features :eql)
           {:keys [editor/contents]} (eql [:editor/contents])]
     (watch-id-for-code state (:text/contents contents) (-> contents :text/range ffirst))))
  ([state source row]
   (let [{:keys [method]} (method-name source row)]
     (get-in @state [:repl/watch method :watches 0]))))
