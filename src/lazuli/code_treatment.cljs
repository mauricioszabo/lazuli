(ns lazuli.code-treatment
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [tango.commands-to-repl.pathom :as pathom]
            [orbit.evaluation :as eval]
            [com.wsscode.pathom3.connect.operation :as connect]
            ["path" :as path]))

(defonce Parser (js/require (path/join js/__dirname "tree-sitter")))
(defonce initialized
  (.init Parser #js {:locateFile #(path/join js/__dirname "tree-sitter.wasm")}))

(p/do!
 initialized
 (p/let [ruby (.. Parser -Language (load (path/join js/__dirname "ruby.wasm")))]
   (def ruby-language ruby)
   (def parser (new Parser))
   (.setLanguage parser ruby-language)
   (def body-query (.query ruby "
(method  (body_statement) @body)
(singleton_method  (body_statement) @body)"))
   (def ^js parent-query (.query ruby "
(module) @parent (class) @parent
(method) @method (singleton_method) @singleton"))
   (def ^js method-name-query (.query ruby (str "(method name: (_) @name) @method "
                                                "(singleton_method name: (_) @name) @method")))
   (def any-query (.query ruby "(_) @any"))
   (def identifier-query (.query ruby "(call) @call (identifier) @identifier"))
   (def call-query (.query ruby "(call) @call"))
   (def ctx-query (.query ruby "
(identifier) @identifier
(constant) @identifier
(instance_variable) @identifier
(class_variable) @identifier
(call (argument_list) @arglist)

\".\" @sep
\"::\" @sep
\":\" @sep
\"[\" @sep
\"]\" @sep
\"(\" @sep
\")\" @sep
\"{\" @sep
\"}\" @sep
"))))

(defn- dissect-node [node]
  (let [myself (.-text node)
        my-type (.-type node)
        parent (.-parent node)
        parent-type (.-type parent)
        [callee sep caller params?] (.-children parent)
        my-children (delay (mapv (fn [^js node]
                                   [(.-text node)
                                    (.-type node)])
                                 (.-children node)))
        callee-txt (.-text callee)
        assign (fn [element ^js val]
                 (if (#{"operator_assignment" "assignment"} (.-type val))
                   (let [[l a] (.-children val)]
                     (assoc element
                            :assign/expression (str (.-text l) " " (.-text a))
                            :assign/left-side (.-text l)))
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
               :sep (-> @my-children second first)
               :callee (ffirst @my-children)
               :callee-type (ffirst @my-children)}
              parent)

      (= "scope_resolution" parent-type)
      (assign {:identifier (.-text caller)
               :type "scope_resolution"
               :sep (.-text sep)
               :callee callee-txt
               :callee-type (.-type callee)}
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
               :callee callee-txt
               :callee-type (.-type callee)}
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
           ^js parsed (.parse parser text)
           ^js q any-query
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
  (let [^js parsed (.parse parser source)
        ^js query parent-query
        captures-res (. query captures (.-rootNode parsed) #js {:row row :column 0})
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

(defn- make-parent [^js editor ^js query [[row col]]]
  (let [range #js {:row row :column col}
        captures (. query captures (.. editor -languageMode -tree -rootNode)
                   range range)]
    {:nodes captures
     :parents (reduce (fn [acc capture]
                        (let [[kind name] (.. capture -node -children)]
                          (if (-> capture .-name (= "parent"))
                            (conj acc (str (.-text kind) " " (.-text name)))
                            acc)))
                      []
                      captures)}))

(connect/defresolver completions [{:keys [editor/data repl/evaluator]}]
  {::connect/output [:completions/var]}
  (p/let [^js editor (:editor data)
          {:keys [nodes parents]} (make-parent editor parent-query (:range data))
          ^js last-node (last nodes)
          parents (cond-> parents
                    (some-> nodes last .-name (= "parent")) butlast)
          inner-code (if (some-> last-node .-node .-type (#{"method" "singleton"}))
                       "instance_methods"
                       "methods")
          inner-code (str/replace (str "( Class.instance_method(:M).bind(self).call +"
                                       " Class.instance_method(:private_M).bind(self).call +"
                                       " Class.instance_method(:protected_M).bind(self).call ).map(&:to_s)")
                                  #"M"
                                  inner-code)
          contents (str (str/join "\n" parents)
                        "\n" inner-code "\n"
                        (str/join "\n" (repeat (count parents) "end")))
          result (eval/evaluate evaluator contents {:filename (:filename data)
                                                    :row (-> data :range first first)
                                                    :plain true})]
    {:completions/var (mapv (fn [candidate]
                              {:text/contents candidate
                               :completion/type :function})
                            result)}))

(defn prepare-modified-top-block [data]
  (p/let [^js editor (:editor data)
          _ (.. editor -languageMode atTransactionEnd)
          {:keys [nodes parents]} (make-parent editor parent-query (:range data))
          ^js last-node (if-let [node (last nodes)]
                          (.-node node)
                          (-> (make-parent editor any-query (:range data)) :nodes (nth 1) .-node))
          parents (cond-> parents
                    (some-> nodes last .-name (= "parent")) butlast)]
    {:parents parents
     :last-node last-node}))

(connect/defresolver line [{:keys [editor/data repl/evaluator]}]
  {::connect/output [{:text/block [:text/contents :text/range]}
                     {:text/modified-block [:text/contents]}]}
  (p/let [{:keys [parents ^js last-node]} (prepare-modified-top-block data)
          end-row (.. last-node -endPosition -row)
          offset (- end-row (ffirst (:range data)))
          lines (str/split-lines (.-text last-node))
          [bef aft] (split-at (- (count lines) offset 1) lines)
          modified-text (str/join "\n" (concat bef ["NREPL.stop!"] aft))
          modified-content (str (str/join "\n" parents)
                                "\n" modified-text "\n"
                                (str/join "\n" (repeat (count parents) "end")))
          original-content (str (str/join "\n" parents)
                                "\n" (.-text last-node) "\n"
                                (str/join "\n" (repeat (count parents) "end")))
          method-name (-> last-node .-children second)
          ^js start (.-startPosition last-node)
          ^js end (.-endPosition last-node)]
    {:text/modified-block {:text/contents modified-content}
     :text/block {:text/range [[(.-row start) (.-column start)]
                               [(.-row end) (.-column end)]]
                  :text/contents original-content}}))

(defn- contextualize-method [parents ^js capture]
  (let [parents-str (reduce (fn [acc capture]
                             (let [[kind name] (.. capture -node -children)]
                               (conj acc (str (.-text kind) " " (.-text name)))))
                     []
                     parents)
        last-node (.-node capture)
        start-pos (.-startPosition last-node)
        end-pos (.-endPosition last-node)
        num-parents (count parents-str)]
    [(str (str/join "\n" parents-str)
          "\n" (-> capture .-node .-text) "\n"
          (str/join "\n" (repeat num-parents "end")))
     [[(- (.-row start-pos) num-parents) 0]
      [(+ 0 (.-row end-pos)) 3]]]))

(connect/defresolver top-blocks [{:keys [text/contents text/range]}]
  {::connect/output [{:text/top-block [:text/contents :text/range]}]}
  (p/let [^js parsed (.parse parser contents)
          node (.-rootNode parsed)
          [[s-row s-col] [e-row e-col]] range
          captures-res (.captures parent-query node
                                  #js {:row s-row :column s-col}
                                  #js {:row e-row :column e-col})
          [parents [capture]] (split-with #(= "parent" (.-name %)) captures-res)
          [modified-block new-range] (cond
                                       capture (contextualize-method parents capture)
                                       (seq parents) (contextualize-method (butlast parents) (last parents))
                                       :not-inside-module
                                       (when-let [^js node (-> any-query ^js
                                                               (.captures node
                                                                          #js {:row s-row :column s-col}
                                                                          #js {:row e-row :column e-col})
                                                               second
                                                               .-node)]
                                         (let [start-pos (.-startPosition node)
                                               end-pos (.-endPosition node)]
                                           [(.-text node)
                                            [[(.-row start-pos) (.-column start-pos)]
                                             [(.-row end-pos) (.-column end-pos)]]])))]
    (.delete parsed)
    (when modified-block
      {:text/top-block {:text/contents modified-block
                        :text/range new-range}})))
