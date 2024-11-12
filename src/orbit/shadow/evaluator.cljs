(ns orbit.shadow.evaluator
  (:require [orbit.evaluation :as eval]
            [orbit.nrepl.evaluator :as nrepl]
            [orbit.serializer :as serializer]
            [clojure.tools.reader :as r]
            [orbit.meta-helper :as meta-helper]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["ws" :as Websocket]
            [cognitect.transit :as t]
            [promesa.core :as p]
            [clojure.edn :as edn]))

(defn- send! [^js ws msg]
  (let [writer (t/writer :json)
        out (t/write writer msg)]
    (.send ws out)))

(defn- listen-to-events! [state]
  (let [{:keys [ws current-id]} @state
        builds (:build->id @state)]
    (doseq [id (-> @state :id->build keys)]
      (send! ws {:op :runtime-print-unsub :to id})
      (send! ws {:op :tap-unsubscribe :to id}))

    (when (get-in @state [:id->build current-id])
      (send! ws {:op :runtime-print-sub :to current-id})
      (send! ws {:op :tap-subscribe :to current-id})
      #_
      (send! ws {:op :cljs-eval
                 :to id
                 :input {:code "(require 'cljs.reader)" :ns 'shadow.user}}))))

(defn- parse-clients! [state {:keys [clients]}]
  (let [shadow-ids (->> clients
                        (group-by #(-> % :client-info :build-id))
                        (map (fn [v] (update v 1 #(mapv :client-id %))))
                        (into {}))
        on-out (:on-output @state)]
    (swap! state assoc
           :clients (reduce (fn [acc client]
                              (assoc acc (:client-id client) client))
                            {}
                            clients)
           :build->id shadow-ids
           :id->build (into {} (for [[build-id ids] shadow-ids
                                     id ids]
                                 [id build-id])))

    ;; Connect to the first client-id we find
    (swap! state assoc :current-id (-> @state :id->build keys first))
    (listen-to-events! state)
    (on-out {:orbit.shadow/clients-changed (:clients @state)})))

(defn- add-id [st client-id build-id msg]
  (-> st
      (assoc-in [:clients client-id] msg)
      (update-in [:build->id build-id] #(conj (or % []) client-id))
      (update :id->build assoc client-id build-id)))

(defn- remove-id [st client-id]
  (let [build-id (get-in st [:id->build client-id])]
    (-> st
        (update :clients dissoc client-id)
        (update :id->build dissoc client-id)
        (update-in [:build->id build-id] #(->> %
                                               (remove (partial = client-id))
                                               vec)))))

(defn- update-builds! [state {:keys [event-op client-id client-info] :as msg}]
  (let [on-out (:on-output @state)]
    (swap! state #(if (= :client-connect event-op)
                    (add-id % client-id (:build-id client-info) msg)
                    (remove-id % client-id)))
    (listen-to-events! state)
    (def state state)
    (on-out {:orbit.shadow/clients-changed (:clients @state)})))

(defn- normalize-fragment [fragment wrap?]
  (cond
    (string? fragment)
    (->> fragment (re-find #"(?ms):result \(do\n\s*(.*)\n\)\}\)\)\s+\(catch") second)

    wrap?
    (-> fragment first last last second second second :result second)

    :else
    fragment))

(defn- edn-deserialize [pending raw-result]
  (let [parsed (try
                 (cond->> (edn/read-string
                           {:default tagged-literal} raw-result))
                 (catch :default _
                   (symbol raw-result)))
        normalized (if (and (:normalize-serialization pending)
                            (satisfies? IAssociative parsed))
                     (-> parsed
                         (update-in [:via 0 :data :form] normalize-fragment (:wrap? pending))
                         (update-in [:via 0 :data :source] normalize-fragment (:wrap? pending)))
                     parsed)]
    (if-let [tag (:wrapped-tag pending)]
      (tagged-literal tag normalized)
      normalized)))

(defn- capture-result! [state msg pending]
  (let [raw-result (:result msg)
        id (:call-id msg)
        final-res (if (:wrap? pending)
                    (try (-> raw-result serializer/deserialize :result)
                      (catch :default _ {:error ::ERROR}))
                    {(:key pending) (edn-deserialize pending raw-result)})]
    (swap! state update :pending-evals dissoc id)
    (p/resolve! (:promise pending) (assoc final-res :id id))))

(defn- get-result! [state msg]
  (swap! state assoc-in [:pending-evals (:call-id msg) :key] :result)
  (send! (:ws @state) {:op :obj-request
                       :call-id (:call-id msg)
                       :to (:from msg)
                       :request-op :edn
                       :oid (:ref-oid msg)}))

(defn- get-error! [state msg disable-wrap?]
  (let [to-merge (cond-> {:key :error
                          :wrapped-tag 'error
                          :normalize-serialization true}
                   disable-wrap? (assoc :wrap? false))]
    (swap! state update-in [:pending-evals (:call-id msg)] merge to-merge)
    (send! (:ws @state) {:op :obj-request
                         :call-id (:call-id msg)
                         :to (:from msg)
                         :request-op :edn
                         :oid (:ex-oid msg)})))

(defn- send-as-error! [state msg pending]
  (let [;raw-result (:result msg)
        id (:call-id msg)]
        ; eval-row (:row pending)
        ; stacktrace (fn [row]
        ;              [:div.children [:a.children {:href "#"
        ;                                           :on-click (list 'fn '[e]
        ;                                                       '(.preventDefault e)
        ;                                                       '(.stopPropagation e)
        ;                                                        (list 'editor/run-callback
        ;                                                              :open-editor
        ;                                                              {:file-name (:file pending)
        ;                                                               :line (+ row eval-row -1)}))}
        ;                              "at " (:file pending) ":" (+ row eval-row)]])
        ; interactive {:html (->> msg
        ;                         :warnings
        ;                         (map (fn [{:keys [warning line msg]}]
        ;                                [:div.rows
        ;                                 [:div.pre
        ;                                  (-> warning name
        ;                                      (str/replace #"-" " ")
        ;                                      str/capitalize)
        ;                                  ": " msg]
        ;                                 (stacktrace line)]))
        ;                         (into [:div.rows
        ;                                [:div.title "Error evaluating command"]
        ;                                [:div.space]]))}]

    (swap! state update :pending-evals dissoc id)
    (p/resolve! (:promise pending) {:error ^:orbit.shadow/error {:orbit.shadow/warnings [msg]}
                                    :id id}))

  #_
  (swap! state update-in [:pending-evals (:call-id msg)] merge
         {:key :error :wrap? false :wrapped-tag 'error})
  #_
  (when-let [{:keys [row file]} (-> @state :pending-evals (get call-id))]
    (let [trace (->> warnings
                     (mapv (fn [{:keys [msg line]}]
                             {:class (str/replace msg #"Use of.* (.*/.*)$" "$1")
                              :method nil
                              :stack-file file
                              :row (dec (+ row line))})))]
      (resolve-pending! state msg
                        (helpers/error-result "Compile Warning"
                                              (->> warnings (map :msg) (str/join "\n"))
                                              trace)))))

(defn- obj-not-found! [state {:keys [call-id] :as msg}]
  #_
  (when-let [{:keys [row file]} (-> @state :pending-evals (get call-id))]
    (resolve-pending! state msg (helpers/error-result "404"
                                                      "Result not found"
                                                      [{:stack-file file
                                                        :class nil
                                                        :method nil
                                                        :row row}]))))

(defn- send-output! [state {:keys [stream text]}]
  (let [on-out (:on-output @state)
        key (if (= :stdout stream) :out :err)]
    (on-out {key text})))

(defn- new-compile-error! [state msg]
  (let [on-out (:on-output @state)]
    (on-out {:orbit.shadow/compile-info msg})
    (if (-> msg :type (= :build-failure))
      (on-out ^:orbit.shadow/error {:orbit.shadow/errors (:report msg)})
      (when-let [warnings (->> msg
                               :info
                               :sources
                               (filter (fn [s] (-> s :warnings seq)))
                               vec
                               not-empty)]
        (on-out ^:orbit.shadow/error {:orbit.shadow/warnings warnings})))))

(defn- old-compile-error! [state msg]
  (let [on-out (:on-output @state)
        build-status (:build-status msg)]
    (on-out {:orbit.shadow/compile-info msg})
    (if (-> build-status :status (= :failed))
      (on-out ^:orbit.shadow/error {:orbit.shadow/errors (-> msg :build-status :report)})
      (when-let [warnings (not-empty (:warnings build-status))]
        (on-out ^:orbit.shadow/error {:orbit.shadow/warnings warnings})))))

(defn- access-denied! [state]
  (.end ^js (:ws @state))
  (p/resolve! (:evaluator @state) {:error :access-denied}))

(defn- send-result! [state pending msg]
  (let [id (:call-id msg)]
    (swap! state update :pending-evals dissoc id)
    (if (and (-> msg :op (= :client-not-found))
             (-> msg :client-id (not= (:current-id @state))))
      (p/resolve! (:promise pending)
                  {:error ^:orbit.shadow/error {:orbit.shadow/error msg} :id id})
      (p/resolve! (:promise pending)
                  {:result msg :id id}))))

(defn- tap! [state msg]
  (send! (:ws @state) {:op :obj-request
                       :call-id (gensym "tap-result")
                       :to (:from msg)
                       :request-op :edn
                       :oid (:oid msg)}))

(defn- unexpected-obj! [state {:keys [result call-id]}]
  ; (tap> [:WOW result call-id])
  ; (def result "[{:orbit.patch/id \"promise-35\"} \"ak6eresultk8eresolvede\"]")
  ; (prn :DONE?)
  (if (str/starts-with? result "[{:orbit.patch/id")
    (let [[patch-info encoded] (edn/read-string result)
          {:keys [result]} (serializer/deserialize encoded)]
      ((:on-output @state) (merge result patch-info))))
  #_
  (let [on-out (:on-output @state)
        tapped? (str/starts-with? (str call-id) "tap-result")
        patch? (str/starts-with? result "#repl-tooling/patch")]
    (if (and tapped? patch?)
      (let [[id res] (edn/read-string {:readers {'repl-tooling/patch identity}} result)]
        (on-out {:patch {:id id :result {:as-text res :result res}}}))
      (on-out {:result {:id call-id
                        :result {:as-text result :result result}
                        :editor-data {:filename "<console>.cljs"
                                      :range [[0 0] [0 0]]
                                      :contents ""},
                        :range [[0 0] [0 0]]}}))))

(defn- should-not-wrap? [code]
  (try
    (let [res (r/read-string {:read-cond :allow} code)]
      (-> res first #{'ns 'require 'import 'use}))
    (catch :default _
      false)))

(defn- evaluate! [state code opts]
  (let [ws (:ws @state)
        client-opts (:options opts)
        the-namespace (:namespace opts 'shadow.user)
        row (:row opts 0)
        file (:filename opts "[EVAL]")
        ^js repl-kind (:kind client-opts :cljs/eval)
        client-id (cond
                    (= "clj" (namespace repl-kind)) :clj
                    (:client-id client-opts) (:client-id client-opts)
                    :else (:current-id @state))
        wrapped-code (if (:no-wrap opts)
                       code
                       (meta-helper/wrapped-command code (:col opts 0)))
        id (:id opts (str (gensym "eval-")))]

    (cond
      (= :clj client-id)
      (eval/-evaluate (:nrepl @state) code opts)

      (nil? client-id)
      (p/resolved {:error ^:orbit.shadow/error {:orbit.shadow/error :no-runtime-selected}
                   :id id})

      :client-id
      (let [prom (p/deferred)
            dont-wrap (should-not-wrap? code)]
        (swap! state update :pending-evals assoc id {:promise prom
                                                     :row row
                                                     :file file
                                                     :wrap? (and (not dont-wrap)
                                                                 (-> opts :no-wrap not))})
        (send! ws {:op :cljs-eval
                   :to client-id
                   :call-id id
                   :input {:code (if dont-wrap code wrapped-code)
                           :filename file
                           :line row
                           :ns the-namespace}})
        prom))))

(defn- send-remote-command! [state op message opts]
  (let [prom (p/deferred)
        row (:row opts 0)
        file (:filename opts "[EVAL]")
        id (:id opts (:call-id message (str (gensym "remote-msg-"))))
        message (assoc message :op op :call-id id)]
    (swap! state update :pending-evals assoc id {:promise prom
                                                 :file file
                                                 :row row
                                                 :wrap? false})
    (send! (:ws @state) message)
    prom))

(defrecord Evaluator [state]
  eval/REPL
  (-evaluate [this command opts]
    (let [repl-kind (get-in opts [:options :kind] :cljs/eval)
          op (-> opts :options :op)]
      (if op
        (if (-> repl-kind namespace (= "clj"))
          (eval/-evaluate (:nrepl @state) command opts)
          (send-remote-command! state op command opts))
        (evaluate! state command opts))))

  ;;FIXME - think a way to implement this
  (-break [_ _])

  (-close [_]
    (swap! state assoc :closed? true)
    (.end (:ws @state))
    (eval/close! (:nrepl @state)))

  (-is-closed [_] (:closed? @state false)))

(defn- send-hello! [state]
  (let [{:keys [ws evaluator]} @state]
    (send! ws {:op :hello :client-info {:editor :repl-tooling}})
    (send! ws {:op :request-clients
               :notify true
               :query [:and
                       [:eq :lang :cljs]
                       [:eq :type :runtime]]})
    (send! ws
           {:op :shadow.cljs.model/subscribe,
            :to 1,
            :shadow.cljs.model/topic :shadow.cljs.model/build-status-update})
    (send! ws
           {:op :shadow.cljs/subscribe,
            :to 1,
            :shadow.cljs/topic :shadow.cljs/worker-broadcast})
    (p/resolve! evaluator (->Evaluator state))))

(defn- treat-ws-message! [state {:keys [op call-id] :as msg}]
  ; (when-not (-> op #{:obj-result :obj-not-found :tap})
  ;   (tap> [:MSG msg]))
  (if-let [pending (get-in @state [:pending-evals call-id])]
    (case op
      :obj-result (capture-result! state msg pending)
      :eval-runtime-error (get-error! state msg false)
      :eval-compile-error (get-error! state (assoc msg :from (:ex-client-id msg 1)) true)
      :eval-compile-warnings (send-as-error! state msg pending)
      :eval-result-ref (get-result! state msg)
      :obj-not-found (obj-not-found! state msg)
      (send-result! state pending msg))
    (case op
      :welcome (send-hello! state)
      :clients (parse-clients! state msg)
      :notify (update-builds! state msg)
      :ping (send! (:ws @state) {:op :pong})
      :runtime-print (send-output! state msg)
      :shadow.cljs.model/sub-msg (old-compile-error! state msg)
      :shadow.cljs/sub-msg (new-compile-error! state msg)
      :access-denied (access-denied! state)
      :tap (tap! state msg)
      :obj-result (unexpected-obj! state msg)
      false
      #_
      (prn :unknown-op op))))

(defn- create-ws-conn! [url state]
  (try
    (let [ws (Websocket. url #js {:rejectUnauthorized false})]
      (swap! state assoc :ws ws)
      (doto ws
            (aset "onmessage" #(let [reader (t/reader :json)
                                     payload (->> ^js % .-data (t/read reader))]
                                 (treat-ws-message! state payload)))
            (aset "onerror" (fn [e]
                              (.end ws)
                              (.log js/console e)
                              (p/reject! (:evaluator @state) e)))
            (aset "onclose" (fn [_]
                              (let [{:keys [on-output should-disconnect?]} @state]
                                (if should-disconnect?
                                  (on-output nil)
                                  (when (:error (create-ws-conn! url state))
                                    (on-output nil))))))
            (aset "end" (fn [_]
                          (swap! state assoc :should-disconnect? true)
                          (.close ws))))
      ws)
    (catch :default e
      {:error e})))

(defn- get-ports-and-token [nrepl host]
  (p/let [server-data (eval/evaluate nrepl "(:http (shadow.cljs.devtools.server.runtime/get-instance))")
          token (:server-token server-data)]
    (if-let [https (:https-port server-data)]
      (str "wss://" host ":" https "/api/remote-relay?server-token=" token)
      (str "ws://" host ":" (:http-port server-data) "/api/remote-relay?server-token=" token))))

(defn connect! [host nrepl-or-nrepl-port on-output]
  (p/let [nrepl (if (number? nrepl-or-nrepl-port)
                  (nrepl/connect! host nrepl-or-nrepl-port on-output)
                  nrepl-or-nrepl-port)
          shadow-uri (get-ports-and-token nrepl host)]
    (let [p (p/deferred)
          state (atom {:should-disconnect? false
                       :nrepl nrepl
                       :evaluator p
                       :on-output on-output
                       :pending-evals {}
                       :build->id {}
                       :id->build {}})
          result (create-ws-conn! shadow-uri state)]
      (if-let [error (:error result)]
        (p/rejected error)
        p))))

(defn change-client-id!
  "Changes the default client ID. This also means it'll start to listen for tap
events (including the auto-resolve of promises) and printing stuff (otherwise,
all `prn` and `println` commands will not return anything to the evaluator).

The default client-id is used when no `:options {:client-id ...}` param is sent."
  [evaluator client-id]
  (swap! (:state evaluator) assoc :current-id client-id)
  (listen-to-events! (:state evaluator))
  evaluator)

(defn current-client-id
 "Gets the default client ID (the one that will be used to send commands if no
`:client-id` options is sent and that is being listened for outputs and tap
statements)."
  [evaluator]
  (when (instance? Evaluator evaluator)
    (:current-id @(:state evaluator))))

(defn get-env-var-code
  "Returns the code to get the ClojureScript compiler environment. This can
be used for a multitude of things, but mainly it's used for tooling support.

It will either use the `current-id` of the evaluator to decide which build-id
it'll use, or it'll use the `client-id` property to define the build-id."
  ([evaluator] (get-env-var-code evaluator nil))
  ([evaluator client-id]
   (when (instance? Evaluator evaluator)
     (let [state @(:state evaluator)
           client-id (or client-id (:current-id state))
           build-id (get-in state [:id->build client-id])]
       (when build-id
         (str "(shadow.cljs.devtools.api/compiler-env " build-id ")"))))))
