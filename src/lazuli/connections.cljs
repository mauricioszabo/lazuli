(ns lazuli.connections
  (:require [reagent.dom :as rdom]
            [clojure.string :as str]
            [tango.editor-helpers :as helpers]
            [orbit.evaluation :as eval]
            [tango.integration.connection :as conn]
            [reagent.core :as r]
            [tango.ui.edn :as edn]
            [lazuli.ui.atom :as atom]
            [lazuli.ui.inline-results :as inline]
            [lazuli.ui.console :as console]
            [tango.ui.console :as tango-console]
            [lazuli.providers-consumers.lsp :as lsp]
            [promesa.core :as p]
            [tango.commands-to-repl.pathom :as pathom]
            [lazuli.providers-consumers.autocomplete :as lazuli-complete]
            [lazuli.providers-consumers.symbols :as symbols]
            [lazuli.code-treatment :as treat]
            [lazuli.ui.interface :as interface]
            [com.wsscode.pathom3.connect.operation :as connect]
            ["path" :as path]
            ["fs" :as fs]))

(defonce connections (atom (sorted-map)))

(defn destroy! [^js panel]
  (.destroy panel)
  (atom/refocus!))

(defn- treat-key [cmd panel event]
  (case (.-key event)
    "Escape" (destroy! panel)
    "Enter" (cmd panel)
    :no-op))

(defn- as-clj [nodelist]
  (js->clj (.. js/Array -prototype -slice (call nodelist))))

(def ^:private local-state (r/atom {:hostname "localhost"}))

(defn- set-port-from-file! []
  (let [paths (into [] (-> js/atom .-project .getPaths (or ["."])))
        port (helpers/get-possible-port paths (:typed-port @local-state))]
    (when port
      (swap! local-state assoc :port port))))

(defn view []
  [:div.native-key-bindings.tab-able
   [:h2 "Connect to nREPL"]
   [:div.block
    [:label "Host: "]
    [:input.input-text {:type "text"
                        :tab-index "1"
                        :value (:hostname @local-state)
                        :on-change #(swap! local-state assoc :hostname (-> % .-target .-value))
                        :on-focus #(-> % .-target .select)}]]
   [:div.block
    [:label "Port: "]
    [:input.input-text {:type "text"
                        :tab-index "2"
                        :placeholder "port"
                        :value (:port @local-state)
                        :on-change #(swap! local-state assoc
                                           :port (-> % .-target .-value int)
                                           :typed-port (-> % .-target .-value int))
                        :on-focus #(-> % .-target .select)}]]])

(defn conn-view [cmd]
  (let [div (. js/document (createElement "div"))
        panel (.. js/atom -workspace (addModalPanel #js {:item div}))]
    (set-port-from-file!)
    (rdom/render [view] div)
    (atom/save-focus! div)
    (doseq [elem (-> div (.querySelectorAll "input") as-clj)]
      (aset elem "onkeydown" (partial treat-key cmd panel)))))

(defn- get-editor-data []
  (when-let [^js editor (atom/current-editor)]
    (let [range (.getSelectedBufferRange editor)
          start (.-start range)
          end (.-end range)]
      {:editor editor
       :contents (.getText editor)
       :filename (.getPath editor)
       :range [[(.-row start) (.-column start)]
               [(.-row end) (cond-> (.-column end)
                              (not= (.-column start) (.-column end)) dec)]]})))

(defn- notify! [{:keys [type title message]}]
  (case type
    :info (atom/info title message)
    :warn (atom/warn title message)
    (atom/error title message)))

(defonce ^:private commands (atom []))
(defn- remove-all-commands! []
  (doseq [^js disposable @commands]
    (.dispose disposable))
  (reset! commands []))

(defn disconnect! [id]
  (when (get @connections id)
    (atom/info "Disconnected" "Disconnected from REPL"))
  (swap! connections dissoc id)
  (remove-all-commands!))

(defn- add-command! [command-name command-function]
  (let [disposable (.. js/atom -commands (add "atom-text-editor"
                                              (str "lazuli:" command-name)
                                              (fn [] (command-function))))]
    (swap! commands conj disposable)))

(def ^:private unused-cmds
  #{:run-test-for-var :run-tests-in-ns
    :load-file :evaluate-block :evaluate-selection
    :go-to-var-definition})

(defn- update-breakpoint! [state result hit?]
  (prn :UPDATE-BREAKPOINT result)
  (let [console ((:get-console (:editor/callbacks @state)))
        file (:editor/filename result)
        file-chars (count file)
        row (-> result :text/selection :text/range ffirst)
        file-text (if (< file-chars 35)
                    file
                    (str "..." (subs file (- file-chars 35))))
        link-text (str " " file-text ":" (inc row))
        link-elem (doto (js/document.createElement "a")
                        (-> .-innerText (set!
                                          (if hit?
                                            link-text
                                            (str link-text " (...waiting...)"))))
                        (-> .-onclick (set! (fn [evt]
                                              (.preventDefault evt)
                                              (.. js/atom -workspace (open file
                                                                           #js {:initialLine row
                                                                                :searchAllPanes true}))))))
        icon (doto (js/document.createElement "button")
                   (.. -classList (add "btn" "icon" "icon-playback-play")))]

    (swap! state assoc :repl/breakpoint
           {:file (:editor/filename result), :row row})
    (doto (. console querySelector ".breakpoint")
          (-> .-innerText (set! ""))
          (. appendChild icon)
          (. appendChild link-elem))))

(defn- update-watch!
  "Updates a watch expression. It `watch` parameter contains:
* `:id` - some ID that identifies the watch expression
* `:text` - a text that will be displayed in the watch expression
* `:callback` - a callback that will be called when we click the button. If `nil`, no button will
  be displayed, and `icon` will be just a badge with the icon
* `:icon` - an icon for the button
* `:file` - the file being watched
* `:row` - a 0-based row

This code will ALWAYS update the watch with the same ID. If none is found, a new one will be
created. If you only send the `:id`, the watch element for that ID will be removed"
  [state {:keys [id text] :as watch}]
  (let [console ((:get-console (:editor/callbacks @state)))
        chars (count text)
        text (if (< chars 55)
               text
               (str "..." (subs text (- chars 55))))
        link-elem (delay
                   (doto (js/document.createElement "a")
                         (-> .-innerText (set! text))
                         (.setAttribute "href" "#")
                         (-> .-onclick (set! (fn [evt]
                                               (.preventDefault evt)
                                               (.. js/atom -workspace (open (:file watch)
                                                                            #js {:initialLine (:row watch)
                                                                                 :searchAllPanes true})))))))
        icon (delay
              (doto (js/document.createElement "button")
                    (.. -classList (add "btn" "icon" (:icon watch)))
                    (-> .-onclick (set! (:callback watch)))))
        parent-elem (. console querySelector (str ".watches *[data-id=" id "]"))]

    (if (-> watch keys (= [:id]))
      (when parent-elem (set! (.-outerHTML parent-elem) ""))
      (let [parent-elem (or parent-elem
                            (doto (js/document.createElement "div")
                                  (.setAttribute "data-id" id)))
            watch-elem (. console querySelector ".watches")]
        (set! (.-innerHTML parent-elem) "")
        (cond
          (:callback watch)
          (. parent-elem appendChild @icon)

          (:icon watch)
          (.. @link-elem -classList (add "inline-block" "status-ignored" "icon" (:icon watch))))
        (. parent-elem appendChild @link-elem)
        (. watch-elem appendChild parent-elem)))))

(defn- clear-old-breakpoint [state]
  (when-let [old-breakpoint (:repl/breakpoint @state)]
    (let [console ((:get-console (:editor/callbacks @state)))]
      (eval/evaluate (:repl/evaluator @state)
                     {}
                     {:plain true
                      :options {:op "eval_resume"}})
      (set! (.. console (querySelector ".breakpoint") -innerHTML) ""))))

(defn- wrap-node [parents ^js capture]
  (let [node (.-node capture)
        texts (map (fn [parent]
                     (let [[kind name] (.-children parent)]
                       (str (.-text kind) " " (.-text name))))
                   parents)
        [node-def [node-body possible-body]] (->> node
                                                  .-children
                                                  (split-with #(not (contains? #{"body_statement" "="} (.-type %)))))
        def-text (->> node-def
                      (map #(.-text ^js %))
                      (str/join " "))
        node-children (cond
                        (nil? node-body) ["nil"]

                        (= "body_statement" (.-type node-body))
                        (->> node-body .-children (mapv #(.-text %)))

                        :single-line-method
                        [(.-text possible-body)])
        parent-name (str/replace (str/join "::" texts) #"(class|module) " "")
        method-name (str parent-name
                         (if (-> capture .-name (= "method")) "#" ".")
                         (->> node-def rest (filter #(-> % .-type (= "identifier"))) first .-text))
        node-lines (cons (str "NREPL.watch!(binding, " (pr-str method-name) ")") node-children)
        num-parents (count parents)
        original-row (-> node .-startPosition .-row)]
    {:row original-row
     :adapted-row (- original-row num-parents)
     :method method-name
     :text (str (str/join "\n" texts)
                "\n" def-text "\n" (str/join "\n" node-lines) "\nend\n"
                (str/join "\n" (repeat num-parents "end")))}))

(defn- get-all-watches [state]
  (p/let [eql (-> @state :editor/features :eql)
          data (p/-> (eql [:editor/data]) :editor/data)
          ^js parsed (.parse treat/parser (:contents data))
          captures-res (. treat/parent-query captures (.-rootNode parsed))

          res
          (->> captures-res
               (reduce (fn [{:keys [parents texts] :as acc} ^js capture]
                         (let [last-parent (some-> (not-empty parents) peek)
                               start-index (.. capture -node -startIndex)
                               parents (->> parents
                                            (filter #(<= start-index (.-endIndex ^js %)))
                                            vec)
                               acc (assoc acc :parents parents)]
                           (if (-> capture .-name (= "parent"))
                             (update acc :parents conj (.-node capture))
                             (update acc :texts conj (wrap-node parents capture)))))
                       {:parents [] :texts []})
               :texts)]
    (.delete parsed)
    res))

(defn- to-id [old-id]
  (-> old-id
      (str/replace #"#" "_HASH_")
      (str/replace #"/" "_SLASH_")
      (str/replace #"\\" "_BSLASH_")
      (str/replace #":" "_TWODOTS_")
      (str/replace #"\." "_DOT_")))

(defn- load-file-and-inspect [state]
  (p/let [watches (get-all-watches state)
          eql (-> @state :editor/features :eql)
          res (eql [:editor/filename :repl/evaluator])
          evaluator (:repl/evaluator res)
          file (:editor/filename res)]
    (doseq [{:keys [adapted-row row method text]} watches]
      (p/do!
       (when-let [watch (get-in @state [:repl/watch method])]
         (swap! state update :repl/watch dissoc method)
         (update-watch! state {:id (to-id method)})
         (eval/evaluate evaluator
                        {:watch_id (:watch/id watch)}
                        {:plain true :options {:op "unwatch"}}))
       (p/then (eval/evaluate evaluator
                              {:code text :file file :line adapted-row}
                              {:plain false :options {:op "eval"}})
               (fn [res]
                 (swap! state assoc-in [:repl/watch method] {:element/id (to-id method)
                                                             :watch/id method
                                                             :watches []})
                 (update-watch! state {:id (to-id method)
                                       :text method
                                       :icon "icon-bookmark"
                                       :file file
                                       :row row})))))))

(defn- load-file-cmd [state]
  (p/let [watches (get-all-watches state)
          eql (-> @state :editor/features :eql)
          res (eql [:editor/filename :repl/evaluator :editor/contents])
          evaluator (:repl/evaluator res)
          file (:editor/filename res)]

    (doseq [{:keys [method]} watches]
      (p/then (eval/evaluate evaluator {:watch_id method} {:plain true :options {:op "unwatch"}})
              #(update-watch! state {:id (to-id method)})))

    (eval/evaluate evaluator (-> res :editor/contents :text/contents) {:filename file})))

(defn- eval-line [state]
  (p/let [eql (-> @state :editor/features :eql)
          id (str (gensym "eval-"))
          editor-info (eql [{:editor/contents [:editor/filename
                                               :text/range
                                               :editor/data
                                               :repl/namespace]}])
          editor-info (-> editor-info
                          :editor/contents
                          (dissoc :com.wsscode.pathom3.connect.runner/attribute-errors)
                          (assoc :id id))
          {:keys [editor/contents repl/evaluator]} (eql [:editor/contents :repl/evaluator])
          current-text (:text/contents contents)
          row (-> contents :text/range ffirst)
          final-col (count (nth (str/split-lines current-text) row))
          dissected (treat/dissect-editor-contents (:text/contents contents)
                                                   [[row final-col] [row final-col]])
          line (cond->> (str (:identifier dissected) (:params dissected))
                 (:sep dissected) (str (:callee dissected) (:sep dissected))
                 (:assign dissected) (str (:assign dissected) " "))
          watch-id (treat/watch-id-for-code state current-text row)
          on-start-eval (-> @state :editor/callbacks :on-start-eval)
          on-eval (-> @state :editor/callbacks :on-eval)]
    (on-start-eval editor-info)
    (p/let [res (eval/evaluate evaluator
                               (cond-> {:id id
                                        :code line
                                        :file (-> editor-info :editor/filename)
                                        :line (-> editor-info :text/range ffirst)}
                                 watch-id (assoc :watch_id watch-id))
                               {:options {:op "eval"}})
            res (merge res editor-info)
            final-result (if (contains? res :result)
                           res
                           (with-meta res {:tango/error true}))]
      (on-eval final-result))))

(defn- eval-selection [state]
  (p/let [eql (-> @state :editor/features :eql)
          id (str (gensym "eval-"))
          editor-info (eql [{:editor/contents [:editor/filename
                                               :text/range
                                               :editor/data
                                               :repl/namespace]}])
          editor-info (-> editor-info
                          :editor/contents
                          (dissoc :com.wsscode.pathom3.connect.runner/attribute-errors)
                          (assoc :id id))
          {:keys [editor/contents repl/evaluator]} (eql [:repl/evaluator
                                                         {:editor/contents [:text/contents
                                                                            :text/range
                                                                            :text/selection]}])
          current-text (:text/contents contents)
          selection (-> contents :text/selection :text/contents)
          watch-id (treat/watch-id-for-code state current-text (-> contents :text/range ffirst))
          on-start-eval (-> @state :editor/callbacks :on-start-eval)
          on-eval (-> @state :editor/callbacks :on-eval)]
    (on-start-eval editor-info)
    (p/let [res (eval/evaluate evaluator
                               (cond-> {:code selection
                                        :id id
                                        :file (-> editor-info :editor/filename)
                                        :line (-> editor-info :text/range ffirst)}
                                 watch-id (assoc :watch_id watch-id))
                               {:options {:op "eval"}})
            res (merge res editor-info)
            final-result (if (contains? res :result)
                           res
                           (with-meta res {:tango/error true}))]
      (on-eval final-result))))

(defn- goto-definition [state]
  (p/let [dissected (treat/dissect-editor-contents state)
          watch-id (treat/watch-id-for-code state)
          evaluator (treat/eql state :repl/evaluator)
          callee (:callee dissected "self")
          editor-info (p/-> (treat/eql state [{:editor/contents [:editor/filename
                                                                 :text/range]}])
                            :editor/contents)
          callee-class (str callee
                            (if (-> dissected :callee-type #{"scope_resolution" "constant"})
                              ".singleton_class"
                              ".class"))
          identifier (str (:identifier dissected)
                          (when (and (-> dissected :type (= "call"))
                                     (:assign dissected))
                            "="))
          code (case (:type dissected)
                 "call" (str callee-class ".__lazuli_source_location(:" identifier ")")
                 "identifier" (str callee-class ".__lazuli_source_location(:" identifier ")")
                 "constant" (str "Object.const_source_location(" (:identifier dissected) ".name)")
                 "scope_resolution" (str callee ".const_source_location(" (pr-str (:identifier dissected)) ")")
                 nil)]
    (when code
      (p/let [[file row] (eval/evaluate evaluator
                                        (cond-> {:code code
                                                 :file (-> editor-info :editor/filename)
                                                 :line (-> editor-info :text/range ffirst)}
                                          watch-id (assoc :watch_id watch-id))
                                        {:plain true :options {:op "eval"}})]
        (when (fs/existsSync file)
          (.. js/atom -workspace (open file
                                       #js {:initialLine (dec row)
                                            :searchAllPanes true})))))))

(defn- add-watch-cmd [state]
  (p/let [{:text/keys [contents range]} (treat/eql state :editor/contents)
          original-row (ffirst range)
          {:keys [method row] :as a} (treat/method-name contents original-row)
          ^js editor (.. js/atom -workspace getActiveTextEditor)
          ^js buffer (.getBuffer editor)
          ^js line (.lineForRow buffer original-row)
          indent (re-find #"^\s+" line)]
    (.setTextInRange buffer
                     (clj->js [[original-row 0] [original-row ##Inf]])
                     (str indent "NREPL.watch!(binding, '" method "')\n" line))))

(defn- observe-editor! [state ^js editor]
  (when editor
    (when-let [display! (-> @state :editor/features :display-watches)]
      (when-let [path (not-empty (.getPath editor))]
        (display! path)))))

(defn- register-commands! [console cmds state]
  (def state state)
  (remove-all-commands!)
  (swap! commands conj (.. js/atom -workspace (observeActiveTextEditor #(observe-editor! state %))))
  (add-command! "clear-console" #(tango-console/clear @console))
  (add-command! "evaluate-line" #(eval-line state))
  (add-command! "evaluate-selection" #(eval-selection state))
  (add-command! "load-file-and-inspect" #(load-file-and-inspect state))
  (add-command! "load-file" #(load-file-cmd state))
  (add-command! "go-to-var-definition" #(goto-definition state))
  (add-command! "add-watch-command" #(add-watch-cmd state))

  (doseq [[key {:keys [command]}] cmds
          :when (not (unused-cmds key))]
    (add-command! (name key) command)))

(defn- diagnostic! [conn-id console output]
  (let [connection (get @connections conn-id)
        parse (-> @connection :editor/features :result-for-renderer)
        append-error (fn [error]
                       (let [div (doto (js/document.createElement "div")
                                       (.. -classList (add "content")))
                             hiccup (parse {:error error})]
                         (rdom/render hiccup div)
                         (tango-console/append console div ["icon-bug"])))]
    (cond
      (-> output meta :orbit.shadow/error)
      (append-error output)

      (:orbit.shadow/compile-info output)
      (tango-console/update-build-status console (:orbit.shadow/compile-info output))

      (:orbit.shadow/clients-changed output)
      (tango-console/update-clients console
                                    connection
                                    (:orbit.shadow/clients-changed output)))))

(defn- start-eval! [console result]
  (inline/create! result)

  (let [div (js/document.createElement "div")]
    (set! (.-id div) (:id result))
    (.. div -classList (add "content" "pending"))
    (set! (.-innerHTML div) "<div class='tango icon-container'><span class='icon loading'></span></div>")
    (tango-console/append console div ["icon-code"])
    (js/setTimeout (fn []
                     (when (.. div -classList (contains "pending"))
                       (.. div -classList (remove "pending"))
                       (set! (.-innerHTML div)
                         "<div class='result error'>Timed out while waiting for a result</div>")))
                   30000)))

(defn- did-eval! [console conn-id result]
  (let [connection (get @connections conn-id)]
    (inline/update! connection result)

    (when-let [div (.querySelector console (str "#" (:id result)))]
      (.. div -classList (remove "pending"))
      (let [parse (-> @connection :editor/features :result-for-renderer)
            hiccup (parse result connection)]
        (set! (.-parsed div) hiccup)
        (rdom/render hiccup div)))))

(defn- open-ro-editor [file-name line col position contents]
  (.. js/atom
      -workspace
      (open file-name position)
      (then #(doto ^js %
                   (aset "isModified" (constantly false))
                   (aset "save" (fn [ & _] (atom/warn "Can't save readonly editor" "")))
                   (.setText contents)
                   (.setReadOnly true)
                   (.setCursorBufferPosition #js [line (or col 0)])))))

(defn- open-editor [{:keys [file-name line contents column]}]
  (let [position (clj->js (cond-> {:initialLine line :searchAllPanes true :location "center" :split "left"}
                                  column (assoc :initialColumn column)))]
    (if contents
      (open-ro-editor file-name line column position contents)
      (.. js/atom -workspace (open file-name position)))))

(defn- get-config []
  (let [config (.. js/atom -config (get "lazuli"))]
    {:eval-mode (-> config (aget "eval-mode") keyword)
     :console-pos (-> config (aget "console-pos") keyword)}))

(defn- summarize-path [path]
  (let [[_ relative] (.. js/atom -project (relativizePath path))
        splitted (str/split relative path/sep)
        [prefix [file]] (split-at (-> splitted count dec) splitted)]
    (str (str/join path/sep (map #(subs % 0 1) prefix))
         path/sep file)))

(defn- append-trace! [console file row]
  (let [traces (.querySelector console "div.traces")
        crumbs (.-children traces)
        txt (str (summarize-path file) ":" (inc row))
        a (doto (js/document.createElement "a")
                (-> .-innerText (set! txt))
                (-> .-onclick (set! (fn [^js evt]
                                      (.preventDefault evt)
                                      (open-editor {:file-name file
                                                    :line row})))))
        span (js/document.createElement "span")]
    (when (-> crumbs .-length (> 0))
      (.append span " > "))
    (.appendChild span a)
    (.appendChild traces span)))

(defn- update-traces! [state]
  (let [traces (:repl/tracings @state)
        console ((:get-console (:editor/callbacks @state)))
        query (.. console (querySelector "input.search-trace") -value)
        reg (re-pattern query)
        filtered (->> traces
                      (filter #(re-find reg (:file %)))
                      (take-last 20))]
    (set! (.. console (querySelector "div.traces") -innerText) "")
    (doseq [{:keys [file row]} filtered]
      (append-trace! console file row))))

(defn- on-out [state key output]
  (when (= "hit_auto_watch" key)
    (let [{:keys [file line]} output
          old-timeout (:repl/tracings-timeout @state)]
      (swap! state update :repl/tracings #(cond-> (conj % {:file file :row line})
                                            (-> % count (> 2000)) (subvec 1)))
      (js/clearTimeout old-timeout)
      (swap! state assoc :repl/tracings-timeout (js/setTimeout #(update-traces! state)))))

  (when (= "hit_watch" key)
    (p/let [eql (-> @state :editor/features :eql)
            contents (p/-> (eql {:file/filename (:file output)}
                                [:file/contents])
                           :file/contents
                           :text/contents)
            {:keys [method row]} (treat/method-name contents (- (:line output) 2))]
      (update-watch! state {:id (to-id method)
                            :text method
                            :icon "icon-eye"
                            :file (:file output)
                            :row row})
      (swap! state assoc-in [:repl/watch method] {:element/id (to-id method)
                                                  :watch/id method
                                                  :watches [(:id output)]}))))

; (declare on-stdout)
#_
(defn- stacktraced-stdout [console ^js div out]
  (if-let [[match before file row] (re-find #"(.+?)([^\s:]+):(\d+)" out)]
    (let [span (js/document.createElement "span")
          a (js/document.createElement "a")]
      (set! (.-innerText span) before)
      (set! (.-innerText a) (str file ":" row))
      (set! (.-onclick a) (fn [^js evt]
                            (.preventDefault evt)
                            (open-editor {:file-name file
                                          :line (dec (js/parseInt row))})))
      (tango-console/append @console
                            (doto div
                                  (.appendChild span)
                                  (.appendChild a))
                            ["icon-quote"])
      (on-stdout console (subs out (count match))))
    (.appendChild div (doto (js/document.createElement "span") (.append out)))))

(defn- big-stdout-thing [console out]
  (let [div (js/document.createElement "div")
        span (tango-console/create-content-span @console (str (subs out 0 80)
                                                              "\033[0m"))
        a (js/document.createElement "a")]
    (set! (.-innerText a) "...")
    (set! (.-onclick a) (fn [^js evt]
                          (.preventDefault evt)
                          (set! (.-innerHTML div) "")
                          (.. div -classList (add "content" "out"))
                          (.appendChild div (tango-console/create-content-span @console out))))
    (.appendChild div span)
    (.appendChild div a)
    div))

(defn- on-stdout [console out]
  ; (when (re-find #"AWS::Account Exists" out)
  ;   (def o out))
  ; (let [stacktraces (re-find #"(.+?)([^\s:]+):(\d+)" out)]
    (cond
      ; stacktraces
      ; (stacktraced-stdout console (js/document.createElement "div") out)

      (-> out count (< 100))
      (tango-console/stdout @console out)

      (re-find #"\s*\033" out)
      (tango-console/append @console
                            (big-stdout-thing console out)
                            ["icon-quote"])

      :normal
      (def s (tango-console/stdout @console out))))

(defn- display-watches [state file]
  (p/let [evaluator (treat/eql state :repl/evaluator)
          res (eval/evaluate evaluator
                             {:file file}
                             {:plain true :options {:op "watches_for_file"}})
          rows (get res "rows")
          con ((:get-console (:editor/callbacks @state)))]

    #_(when (seq rows))
    (set! (.. con (querySelector ".watches") -innerHTML) "")
    (doseq [row rows
            :let [txt (str file ":" (inc row))]]
      (update-watch! state {:id (to-id txt)
                            :text txt
                            :icon "icon-eye"
                            :file file
                            :row row}))
    rows))

(defn- to-remove [flat-out]
  (let [out-str (str flat-out)]
    (or (re-find #"(:var|:repl/kind|:repl/cljs-env|:repl/namespace|:definition|:completions)" out-str)
        (re-find #":text/(ns|current|.*block|.*namespace)" out-str))))

(defn- remove-non-ruby-resolvers [resolvers]
  (->> resolvers
       (remove (fn [resolver]
                 (->> resolver :config ::connect/output
                      (some (fn [out]
                              (if (map? out)
                                (->> out keys first to-remove)
                                (to-remove out)))))))))

(defrecord AdaptedREPL [evaluator]
  eval/REPL
  (-evaluate [this code options]
    (prn :OPTS options)
    (eval/-evaluate evaluator code (assoc options :no-wrap true)))
  (-break [this kind] (eval/-break evaluator kind))
  (-close [this] (eval/-close evaluator))
  (-is-closed [this] (eval/-is-closed evaluator)))

(defn- adapted-repl [editor-state {:repl/keys [repl/evaluator]}]
  (p/let [{:keys [editor/callbacks]} @editor-state
          config-dir (:config/directory @editor-state)
          editor-data ((:editor-data callbacks))
          is-config? (-> config-dir
                         (path/relative (:filename editor-data))
                         (str/starts-with? "..")
                         not)]
     {:repl/evaluator (if is-config?
                        (-> @editor-state :editor/features :interpreter/evaluator)
                        (->AdaptedREPL evaluator))}))
                        ; (:repl/evaluator @editor-state))}))
                        ; (-> @editor-state :editor/features :interpreter/evaluator)
                        ; (->AdaptedREPL (:repl/evaluator @editor-state)))}))

(defn- resolvers-from-state [editor-state]
  (p/let [{:keys [editor/callbacks]} @editor-state
          config-dir (:config/directory @editor-state)
          editor-data ((:editor-data callbacks))
          config ((:get-config callbacks))
          not-found :com.wsscode.pathom3.connect.operation/unknown-value
          is-config? (-> config-dir
                         (path/relative (:filename editor-data))
                         (str/starts-with? "..")
                         not)]
    {:editor/data (or editor-data not-found)
     :config/eval-as (if is-config? :clj (:eval-mode config))
     :config/project-paths (vec (:project-paths config))
     :repl/evaluator (if is-config?
                       (-> @editor-state :editor/features :interpreter/evaluator)
                       (->AdaptedREPL (:repl/evaluator @editor-state)))
     :config/repl-kind (-> @editor-state :repl/info :kind)}))

(defn- update-resolvers! [state]
  (swap! state update-in [:pathom :global-resolvers] remove-non-ruby-resolvers)
  (let [add-pathom-resolvers (-> @state :editor/features :pathom/add-pathom-resolvers)
        remove-resolver (-> @state :editor/features :pathom/remove-resolvers)
        add-resolver (-> @state :editor/features :pathom/add-resolver)
        compose-resolver (-> @state :editor/features :pathom/compose-resolver)]
    (add-pathom-resolvers [treat/top-blocks treat/line treat/completions])
    (compose-resolver {:inputs [] :outputs [:repl/evaluator]} #(adapted-repl state %))))

(defn connect-nrepl!
  ([]
   (conn-view (fn [panel]
                (connect-nrepl! (:hostname @local-state)
                                (:port @local-state))
                (destroy! panel))))
  ([host port]
   (p/let [id (-> @connections keys last inc)
           console (atom nil)
           callbacks {:on-disconnect #(disconnect! id)
                      ;; FIXME - move everything that uses @console to a different callback?
                      :on-stdout (fn on-out [%]
                                   (on-stdout console %))
                      :on-stderr #(tango-console/stderr @console %)
                      :on-diagnostic #(diagnostic! id @console %)
                      :on-start-eval #(start-eval! @console %)
                      :on-eval #(did-eval! @console id %)
                      :on-out #(on-out %1 %2 %3)
                      ;; Use the new callback
                      :register-commands #(register-commands! console %1 %2)
                      ;; Below
                      :get-console #(deref console)
                      :get-rendered-results #(concat (inline/all-parsed-results)
                                                     (tango-console/all-parsed-results @console))
                      :notify notify!
                      :open-editor open-editor
                      :prompt (partial prn :PROMPT)
                      :get-config #(get-config)
                      :editor-data #(get-editor-data)
                      :config-directory (path/join (. js/atom getConfigDirPath) "lazuli")}
           repl-state (conn/connect! host port callbacks)]
     (when repl-state
       (swap! repl-state assoc-in [:editor/features :display-watches] #(display-watches repl-state %))
       (swap! repl-state assoc :repl/tracings [])
       (reset! lazuli-complete/state repl-state)
       (reset! symbols/find-symbol (-> @repl-state :editor/features :find-definition))
       (update-resolvers! repl-state)

       (p/then (console/open-console (.. js/atom -config (get "lazuli.console-pos"))
                                     #((-> @repl-state :editor/commands :disconnect :command)))
               (fn [c]
                 (set! (.. c (querySelector ".details") -innerHTML)
                   (str "<div class='title'>Trace</div>"
                        "<div><input class='search-trace input-text'></div>"
                        "<div class='traces'></div>"
                        "<div class='title'>Watch Points</div><div class='watches'></div>"
                        "<div class='title'>Breakpoint</div><div class='breakpoint'></div>"))
                 (set! (.. c (querySelector "input.search-trace") -onchange) #(update-traces! repl-state))
                 (tango-console/clear c)
                 (reset! console c)))
       (swap! connections assoc id repl-state)))))
