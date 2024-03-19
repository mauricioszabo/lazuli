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
            ["path" :as path]))

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
    :load-file :evaluate-block})

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
        text (if (< chars 35)
               text
               (str "..." (subs text (- chars 35))))
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
        ^js node-body (->> node
                           .-children
                           (filter #(-> % .-type (= "body_statement")))
                           first)
        node-def (->> node .-children
                      (take-while #(-> % .-type (not= "body_statement")))
                      (map #(.-text %))
                      (str/join " "))
        node-children (some->> node-body .-children (mapv #(.-text %)))
        parent-name (str/replace (str/join "::" texts) #"(class|module) " "")
        method-name (str parent-name
                         (if (-> capture .-name (= "method")) "#" ".")
                         (-> node .-children (nth 1) .-text))
        node-lines (when node-children
                     (conj (pop node-children)
                           (str "__ret = begin;" (peek node-children) "\nend")
                           (str "NREPL.watch!(binding, " (pr-str method-name) ")")
                           "__ret"))
        num-parents (count parents)
        original-row (-> node .-startPosition .-row)]
    {:row original-row
     :adapted-row (- original-row num-parents)
     :method method-name
     :text (str (str/join "\n" texts)
                "\n" node-def "\n" (str/join "\n" node-lines) "\nend\n"
                (str/join "\n" (repeat num-parents "end")))}))

(defn- get-all-watches [state]
  (p/let [eql (-> @state :editor/features :eql)
          data (p/-> (eql [:editor/data]) :editor/data)
          captures-res (. pathom/parent-query captures
                         (.. pathom/parser (parse (:contents data)) -rootNode))]
    (->> captures-res
         (reduce (fn [{:keys [parents texts] :as acc} ^js capture]
                   (let [last-parent (some-> (not-empty parents) peek)
                         start-index (.. capture -node -startIndex)
                         parents (->> parents
                                      (filter #(<= start-index (.-endIndex ^js %)))
                                      vec)
                         acc (assoc acc :parents parents)]
                     (println (.. capture -node -text))
                     (if (-> capture .-name (= "parent"))
                       (update acc :parents conj (.-node capture))
                       (update acc :texts conj (wrap-node parents capture)))))
            {:parents [] :texts []})
         :texts)))

(defn- to-id [old-id]
  (-> old-id
      (str/replace #"#" "_HASH_")
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

(defn- method-name [source row]
  (let [parsed (.parse pathom/parser source)
        captures-res (. pathom/parent-query captures (.-rootNode parsed) #js {:row row :column 0})
        [parents [capture]] (split-with #(= "parent" (.-name %)) captures-res)
        ^js node (.-node capture)
        texts (map (fn [parent]
                     (let [[kind name] (.. parent -node -children)]
                       (str (.-text kind) " " (.-text name))))
                   parents)
        parent-name (str/replace (str/join "::" texts) #"(class|module) " "")
        original-row (-> node .-startPosition .-row)
        is-method? (-> capture .-name (= "method"))
        method-part (-> node .-children (nth 1) .-text)]
    (.delete parsed)
    {:row original-row
     :method (str parent-name
                  (if is-method? "#" ".")
                  method-part)}))

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
          line (nth (str/split-lines current-text) row)
          {:keys [method]} (method-name current-text row)
          watch-id (get-in @state [:repl/watch method :watches 0])
          on-start-eval (-> @state :editor/callbacks :on-start-eval)
          on-eval (-> @state :editor/callbacks :on-eval)]
    (on-start-eval editor-info)
    (if watch-id
      (p/let [res (eval/evaluate evaluator
                                 {:code line :watch_id watch-id :id id}
                                 {:options {:op "eval"}})
              res (merge res editor-info)
              final-result (if (contains? res :result)
                             res
                             (with-meta res {:tango/error true}))]
        (on-eval final-result))
      (on-eval (merge editor-info
                      ^:tango/error {:error "We haven't watched this method yet - results will be unreliable"})))))
(defn- register-commands! [console commands state]
  (remove-all-commands!)
  (add-command! "clear-console" #(tango-console/clear @console))
  (add-command! "evaluate-line" #(eval-line state))
  (add-command! "load-file-and-inspect" #(load-file-and-inspect state))
  (add-command! "load-file" #(load-file-cmd state))

  (doseq [[key {:keys [command]}] commands
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
  (let [position (clj->js (cond-> {:initialLine line :searchAllPanes true}
                                  column (assoc :initialColumn column)))]
    (if contents
      (open-ro-editor file-name line column position contents)
      (.. js/atom -workspace (open file-name position)))))

(defn- get-config []
  (let [config (.. js/atom -config (get "lazuli"))]
    {:eval-mode (-> config (aget "eval-mode") keyword)
     :console-pos (-> config (aget "console-pos") keyword)}))

(defn- on-out [state key output]
  (when (= "hit_watch" key)
    (prn :HIT)
    (p/let [eql (-> @state :editor/features :eql)
            contents (p/-> (eql {:file/filename (:file output)}
                                [:file/contents])
                           :file/contents
                           :text/contents)
            {:keys [method row]} (method-name contents (- (:line output) 2))]
            ; captures-res (. pathom/parent-query captures
            ;                (.. pathom/parser (parse contents) -rootNode)
            ;                #js {:row (- (:line output) 2) :column 0})
            ; [parents [node]] (split-with #(= "parent" (.-name %)) captures-res)
            ; {:keys [method row]} (wrap-node (map #(.-node %) parents) node)]
      (prn :M method)
      (update-watch! state {:id (to-id method)
                            :text method
                            :icon "icon-eye"
                            :file (:file output)
                            :row row})
      (swap! state assoc-in [:repl/watch method] {:element/id (to-id method)
                                                  :watch/id method
                                                  :watches [(:id output)]}))))

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
                                   (prn :STDOUT!!!!)
                                   (tango-console/stdout @console %))
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
       (reset! lazuli-complete/tango-complete
               (-> @repl-state :editor/features :autocomplete))
       (reset! symbols/find-symbol
               (-> @repl-state :editor/features :find-definition))

       (p/then (console/open-console (.. js/atom -config (get "lazuli.console-pos"))
                                     #((-> @repl-state :editor/commands :disconnect :command)))
               (fn [c]
                 (set! (.. c (querySelector ".details") -innerHTML)
                   (str "<div class='title'>Watch Points</div><div class='watches'></div>"
                        "<div class='title'>Breakpoint</div><div class='breakpoint'></div>"))
                 (tango-console/clear c)
                 (reset! console c)))
       (swap! connections assoc id repl-state)))))
