(ns lazuli.test-helpers
  (:require [promesa.core :as p]))

(defmacro with-pulsar [[binding & {:as config}] & body]
  (let [debug (:debug config)]
    `(p/let [~binding (run-pulsar!)]
       (when ~debug (def ~binding ~binding))
       (p/finally (p/do! ~@body)
                  #(when-not ~debug (.close (:app ~binding)))))))

(defmacro changing [[binding fun] & body]
  `(let [~binding (promise-to-change (fn [] ~fun))]
     (p/do!
      ~@body
      ~binding)))

#_
(defmacro expect-locator! [playwright selector assertion]
  `(let [{:keys [^js page#]} ~playwright]
     (. ((~'js* "expect")) (.first (.locator page# ~selector))
       ~assertion)))
     ; (. (~'expect (.. page# (~'locator ~selector) ^js first)) ~assertion)))
