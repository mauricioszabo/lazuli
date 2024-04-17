(ns orbit.evaluation
  (:require [promesa.core :as p]))

(defprotocol REPL
  (-evaluate [this code options])
  (-break [this kind])
  (-close [this])
  (-is-closed [this]))

(defn closed?
  "Checks if the evaluator is already closed"
  [evaluator]
  (-is-closed evaluator))

(defn evaluate
  "Evaluate a code in a specific REPL, and return a promise that will either
resolve with `{:result <the result of evaluation>}` will be rejected with
`{:error <the error>}`. If the REPL is also not connected, it'll return an
`^:orbit.error {:error {:orbit.repl/no-runtime :not-connected}}` result

Options can be:
* `:succeed` - if `true`, in case the evaluation returns an error, instead of getting
the promise to be _rejected_ it'll actually keep as _accepted_ (but with the
same `{:error <the error>}` format)
* `:plain` - if `true`, it'll not return the result or exception inside a
map. This option is incompatible with `:pass`, `:succeed`, and can cause issues
if you run evaluation commands like nREPL middlewares or Shadow-CLJS commands
* `:namespace` - a symbol defining the namespace
* `:row` - a 0-based row of the file being evaluated
* `:col` - a 0-based column of the file being evaluated
* `:filename` - a string with the filename being evaluated
* `:pass` - a map with additional parameters that will be passed to the result
* `:no-wrap` - some of the things like `ns` and `require` forms don't work correctly
  on ClojureScript, and may not work on Clojure for some edge cases. If you pass
  this argument, they will be passed as-is, without wrapping around any of the
  serialization commands
* `:options` - specific evaluation options that can be passed to the Evaluator
and may change things, such as:

**For Both**
* `:kind` - There are two sessions - :eval and :aux. Both accept a namespace, which
  can be `clj` or `cljs`, meaning that are 4 options in total: `:clj/eval`,
  `:cljs/eval`, `:clj/aux` and `:cljs/aux`. Can return an error
  `^:orbit.error {:orbit.error/no-runtime <the kind>}`.

**For nREPL**
* `:op` - the operation (could be from a middleware) that will be run. If you pass
this argument, `code` will need to be a `map` with the full operation

**For Shadow-CLJS**
* `:client-id` - the current Javascript runtime you're evaluating code into
* `:op` - the shadow-cljs remote command you might want to run. If you pass this
argument, `code` will need to be a `map` with the full command code"
  ([evaluator code] (evaluate evaluator code {:plain true}))
  ([evaluator code options]
   ;; I know it's weird, but if we don't do this **sending** the evaluation
   ;; becomes async, which might be a problem in situations where we dispatch
   ;; a command and immediately break after.
   (if (closed? evaluator)
     (let [res ^:orbit.error {:error {:orbit.repl/no-runtime :not-connected}}]
       (if (:plain options) (p/rejected (:error res)) (p/resolved res)))
     (let [result (-evaluate evaluator code options)]
       (p/let [result result
               pass (:pass options)
               result (cond-> result pass (assoc :pass pass))]
         (cond
           (not (:plain options)) result
           (contains? result :result) (:result result)
           :error (p/rejected (:error result))))))))

(defn break!
  "Stops the evaluation of the current code - only works for some implementations"
  ([evaluator]
   (-break evaluator :clj/eval))
  ([evaluator kind]
   (-break evaluator kind)))

(defn close!
  "Close the connection with the evaluator"
  [evaluator]
  (-close evaluator)
  true)
