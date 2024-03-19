(ns orbit.nrepl.evaluator
  (:require [orbit.nrepl.bencode :as bencode]
            [orbit.evaluation :as eval]
            [clojure.edn :as edn]
            [clojure.tools.reader :as r]
            [promesa.core :as p]
            [orbit.serializer :as serializer]
            [orbit.meta-helper :as meta-helper]
            [clojure.walk :as walk]
            ["net" :as net]))

(defn- no-wrap-form? [code]
  (try
    (let [res (r/read-string {:read-cond :allow} code)]
      (-> res first (= 'ns)))
    (catch :default _
      false)))

(defn- eval-op [command opts no-wrap?]
  (let [id (:id opts (str (gensym "eval-")))
        op {:op "eval"
            :code (cond
                    (:no-wrap opts) (str #_"\n" command)
                    no-wrap? (str "(do \n" command ")")
                    :wrap? (meta-helper/wrapped-command command (:col opts 0)))
            ; :nrepl.middleware.print/stream? 1
            :id id}]
    (cond-> op
      (:namespace opts) (assoc :ns (:namespace opts))
      (:filename opts) (assoc :file (:filename opts))
      ;; We don't decrement row here because of wrapping
      (:row opts) (assoc :line (:row opts 0)))))

(defrecord REPL [^js conn state]
  eval/REPL
  (-evaluate [this command opts]
    (if (some-> opts :options :kind namespace (= "cljs"))
      (p/resolved {:error ^:orbit.error {:orbit.repl/no-runtime (-> opts :options :kind)}
                   :id (:id opts (str (gensym "eval-")))})
      (let [session-id (get-in @state [:sessions (get-in opts [:options :kind] :clj/eval)])
            nrepl-op (-> opts :options :op)
            no-wrap? (or (:no-wrap opts)
                         (:never-wrap @state)
                         (no-wrap-form? command)
                         nrepl-op)
            op (if nrepl-op
                 (assoc command :op nrepl-op :id (str (gensym "op")))
                 (eval-op command opts no-wrap?))
            op (cond-> op session-id (update :session #(or % session-id)))
            promise (p/deferred)]
        ; (prn '--> op)
        (swap! state assoc-in [:pending (:id op)] {:promise promise
                                                   :opts (assoc opts :no-wrap no-wrap?)})
        (swap! state assoc :done (p/deferred))
        (.write conn (js/Buffer.from (bencode/encode op)) "binary")
        promise)))

  (-break [_ kind]
    (when-let [session-id (-> @state :sessions (get kind))]
      (.write conn (bencode/encode {:op :interrupt :session session-id}) "binary")))

  (-close [_]
    (swap! state assoc :closed? true)
    (p/do!
     (p/race [(:done @state) (p/delay 1000)])
     (.destroy conn)))
  (-is-closed [_] (:closed? @state false)))

(def ^:private detection (str "#?("
                              ":bb :bb "
                              ":joker :joker "
                              ":clje :clje "
                              ":cljs :cljs "
                              ":cljr :cljr "
                              ":clj :clj "
                              ":default :unknown"
                              ")"))

(defn- connect-repl! [host port]
  (let [promise (p/deferred)]
    (let [buffer (atom [])
          ^js conn (. net createConnection port host)]
      (.on conn "connect" #(p/resolve! promise {:buffer buffer :conn conn}))
      (.on conn "data" #(swap! buffer conj (str %)))
      (.on conn "error" #(p/reject! promise (. ^js % -errno)))
      (.on conn "close" #(reset! buffer [:closed]))
      promise)))

(defn- send-result! [id promise opts value]
  (p/resolve!
   promise
   (if (:no-wrap opts)
     {:id id
      :result (try
                (cond->> (edn/read-string {:default tagged-literal} value))
                (catch :default _ (symbol value)))}
     (let [decoded (-> value serializer/deserialize :result)]
       (assoc decoded :id id)))))

(defn- treat-output [state buffer contents]
  (when (seq contents) (reset! buffer []))
  (let [decode (:decoder @state)
        on-output (:on-output @state)]
    (if (= [:closed] contents)
      (do
        (remove-watch buffer :nrepl-evaluator)
        (on-output nil))
      (doseq [row contents
              decoded (decode row)
              :let [id (get decoded "id")
                    statuses (get decoded "status")
                    err-path [:pending id :error]
                    clearing-promise (fn [f]
                                       (f)
                                       (swap! state update :pending dissoc id))]]
        ; (prn '<-- decoded)
        ;; Conditions that don't need an ID
        (cond
          (contains? decoded "out")
          (on-output {:out (get decoded "out")})

          (contains? decoded "err")
          (let [err (get decoded "err")]
            (if (get-in @state [:pending id])
              (if-let [last-error (get-in @state err-path)]
                (do
                  (when-not (= ::not-an-error last-error)
                    (swap! state assoc-in err-path ::not-an-error)
                    (on-output {:err last-error}))
                  (on-output {:err err}))
                (swap! state assoc-in err-path err))
              (on-output {:err err})))

          (contains? decoded "op")
          (on-output {(get decoded "op") (walk/keywordize-keys decoded)}))

        ;; Conditions that DO
        (when-let [{:keys [promise opts] :as pending} (get-in @state [:pending id])]
          (cond
            (some #{"unknown-op"} statuses)
            (do
              (p/resolve! (:done @state) true)
              (clearing-promise #(p/resolve! promise
                                             {:error (-> decoded
                                                         (update "status"
                                                                 (fn [v]
                                                                   (->> v
                                                                        (remove #{"done" "error"})
                                                                        (mapv keyword))))
                                                         walk/keywordize-keys)})))

            (contains? decoded "value")
            (do
              (when-let [last-error (get-in @state err-path)]
                (when (not= ::not-an-error last-error)
                  (on-output {:err last-error})))
              (clearing-promise #(send-result! id promise opts (get decoded "value"))))

            (contains? decoded "ex")
            (clearing-promise
             #(p/resolve! promise
                          {:id id
                           :error (serializer/->RawData (str (get decoded "ex")
                                                             ": "
                                                             (:error pending)))}))

            (some #{"namespace-not-found"} statuses)
            (clearing-promise
             #(p/resolve!
               promise {:id id
                        :error (ex-info (str "Namespace " (-> pending :opts :namespace)
                                             " not found. Maybe you neeed to load-file,"
                                             " or evaluate the ns form?")
                                        {:not-found (-> pending :opts :namespace)})}))

            (some #{"interrupted"} statuses)
            (clearing-promise #(p/resolve! promise {:error (ex-info "Evaluation interrupted" {})
                                                    :id id}))

            (some #{"done"} statuses)
            (let [key (if (some #{"error"} statuses)
                        :error
                        :result)]
              (p/resolve! (:done @state) true)
              (clearing-promise #(p/resolve! promise {key decoded :id id})))))))))

(defn connect!
  "Connects to a nREPL server. Options are host, port, which are self-explanatory, and
`on-output`, which is a callback that will be called either with a map or `nil`. If it's called
with `nil`, the connection is closed; if it's called with a map, it can contain `:out`, `:err`,
which is the result of STDOUT and STDERR respectively, or any other key, which can be a
REPL-side event (the key will be the `op` field)

It can also accept `options`, which will configure the nREPL in some way. For now, it only
accepts the `:never-wrap` option, to disable wrapping completely. A different way to disable
all wrapping is to assoc the key `:no-wrap` into the `:state` atom of the REPL."
  ([host port on-output] (connect! host port on-output {}))
  ([host port on-output options]
   (p/let [{:keys [conn buffer]} (connect-repl! host port)
           state (atom (assoc options
                              :on-output on-output
                              :decoder (bencode/decoder)))
           evaluator (->REPL conn state)
           _ (add-watch buffer :nrepl-evaluator #(treat-output state buffer %4))
           clone-op {:options {:op :clone} :plain true}
           sessions (p/all [(eval/evaluate evaluator {} clone-op)
                            (eval/evaluate evaluator {} clone-op)])]
     (swap! state assoc :sessions {:clj/eval (get-in sessions [0 "new-session"])
                                   :clj/aux (get-in sessions [1 "new-session"])})
     evaluator)))
