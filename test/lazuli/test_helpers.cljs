(ns lazuli.test-helpers
  (:require-macros [lazuli.test-helpers :as me])
  (:require ["playwright" :as play]
            ["@playwright/test" :refer [expect]]
            ["path" :as path]
            ["fs" :as fs]
            [clojure.string :as str]
            [promesa.core :as p]))

(def fixture-path (path/join js/__dirname ".." "test" "ruby_example"))
(def electron (.-_electron play))

(defn run-command! [{:keys [^js page]} command]
  (p/let [_ (.. page (locator "atom-workspace") (press "Control+Shift+p"))
          _ (. (expect (.locator page "atom-panel.modal:visible .command-palette")) toBeVisible)
          ^js palette (.locator page ".command-palette atom-text-editor.is-focused")]
    (.type palette command)
    (.. page (locator ".selected div" #js { :hasText command}) first click)
    (. (expect (.locator page ".modal:visible .command-palette")) toBeHidden)))

(defn locate! [{:keys [^js page]} selector]
  (.. page (locator selector)))

(defn expect-first! [playwright selector]
  (expect (.first (locate! playwright selector))))

(defn expect! [playwright selector]
  (expect (locate! playwright selector)))

(defn run-pulsar! []
  (let [env (.-env js/process)
        config (clj->js {:args ["--no-sandbox" fixture-path]
                         :cwd fixture-path
                         :env env
                         :timeout 50000
                         :executablePath "/opt/Pulsar/pulsar"})]
    (p/let [^js app (.launch electron config)
            ^js page (.firstWindow app)
            return {:app app :page page}]
      (.toBeVisible (expect-first! return ".tab-bar"))
      (run-command! return "Tabs: Close All Tabs")
      (run-command! return "Tabs: Close All Tabs")
      (run-command! return "Tabs: Close All Tabs")
      (run-command! return "Tabs: Close All Tabs")
      (.toHaveCount (expect! return ".texteditor") 0)
      return)))

(defn type!
  ([play text] (type! play "atom-text-editor.is-focused" text))
  ([{:keys [^js page]} locator text]
   (p/let [^js editor (.locator page locator)]
     (. (expect editor) toBeVisible)
     (.type editor text))))

(defn locate-all-text! [{:keys [^js page]} locator]
  (p/let [elements (.. page (locator locator) all)]
    (p/all
     (for [^js e elements]
       (.innerText e)))))

(defn get-notifications! [{:keys [^js page]}]
  (p/let [nots (.. page (locator "atom-notification") all)]
    (p/all
     (for [^js n nots]
       (p/let [title (.. n (locator ".message.item") innerText)
               details (.. n (locator ".item .detail-content") innerText)]
         {:title title
          :details details})))))

(defn promise-to-change [fun]
  (p/let [old (fun)]
    (p/loop [tries 0]
      (p/let [new (fun)]
        (cond
          (not= new old) new
          (<= tries 50) (p/do! (p/delay 20) (p/recur (inc tries)))
          :exhausted-tries (p/rejected (ex-info (str "Didn't change result of " old) {})))))))

(defn open! [{:keys [^js page]} file]
  (p/let [_ (.. page (locator "atom-workspace") (press "Control+p"))
          _ (. (expect (.locator page "atom-panel.modal:visible")) toBeVisible)
          ^js palette (.locator page ".fuzzy-finder atom-text-editor.is-focused")]
    (.type palette file)
    (.. page (locator ".selected div" #js { :hasText file}) first click)
    (. (expect (.locator page ".modal:visible")) toBeHidden)
    (. (expect (.locator page "a.current-path")) toContainText file)))

(defn goto-line! [{:keys [^js page] :as play} rowcol]
  (p/let [_ (run-command! play "Go To Line: Toggle")]
    (type! play ".from-top .go-to-line atom-text-editor.is-focused" (str rowcol "\n"))))

(def ^:private tr-keys {:f1 "F1"
                        :esc "Escape"
                        :up "ArrowUp"
                        :down "ArrowDown"
                        :left "ArrowLeft"
                        :right "ArrowRight"
                        :backspace "Backspace"
                        :tab "Tab"
                        :delete "Delete"
                        :home "Home"
                        :insert "Insert"})

(def ^:private tr-mods {:shift "Shift"
                        :ctrl "Control"
                        :alt "Alt"
                        :meta "Meta"})

(defn press! [{:keys [^js page]} & keys]
  (let [[modifiers ks] (split-with tr-mods keys)
        modifiers (mapv tr-mods modifiers)
        keys (map #(str/join "+" (conj modifiers (tr-keys % %))) ks)]
    (doseq [key keys]
      (.. page -keyboard (press key)))))

(defn reset-editor! [{:keys [^js page]}]
  (p/let [path (.evaluate page "atom.workspace.getActiveTextEditor().getPath()")
          contents (fs/readFileSync path #js {:encoding "UTF-8"})]
    (.evaluate page (str "atom.workspace.getActiveTextEditor().setText(" (pr-str contents) ")"))))

;;; Lazuli Specific

(defn connect-modal! [pulsar]
  (let [connect-modal (fn []
                        (p/let [txts (locate-all-text! pulsar ".modal")]
                          (some #(re-find #"Connect to nREPL" %) txts)))]
    (me/changing [_ (connect-modal)]
      (run-command! pulsar "Lazuli: Connect"))))

(defn inline-results! [pulsar]
  (locate-all-text! pulsar ".lazuli.result"))

(defn element-with! [pulsar selector text]
  (p/loop [tries 0]
    (p/let [texts (locate-all-text! pulsar selector)
            match (some #{text} texts)]
      (cond
        match match
        (<= tries 50) (p/do! (p/delay 20) (p/recur (inc tries)))
        :exhausted-tries (p/rejected (ex-info (str "Didn't find a result with " text) {:results texts}))))))

(defn result-with! [pulsar text]
  (element-with! pulsar ".lazuli.result" text))

(defn type-and-eval!
  ([pulsar text] (type-and-eval! pulsar text "Line"))
  ([pulsar text kind]
   (p/do!
    (type! pulsar text)
    (run-command! pulsar (str "Lazuli: Evaluate ")))))

(defn type-and-eval-resulting!
  ([pulsar text result] (type-and-eval-resulting! pulsar text "Line" result))
  ([pulsar text kind result]
   (p/let [inlines (type-and-eval! pulsar text kind)]
     (result-with! pulsar result))))
