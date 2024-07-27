(ns lazuli.evaluation-tests
  (:require [clojure.test :refer [deftest]]
            [check.async :refer [testing async-test check]]
            [matcher-combinators.matchers :as m]
            ["fs" :as fs]
            [promesa.core :as p]
            [lazuli.test-helpers :as h]))

(deftest evaluate-commands
  (h/with-pulsar [pulsar]
    (async-test "can evaluate commands in a Ruby REPL" {:timeout 20000 :teardown (h/reset-editor! pulsar)}
      (h/open! pulsar "main.rb")
      (h/connect-modal! pulsar)
      (h/changing [notifications (h/get-notifications! pulsar)]
        (h/type! pulsar ".modal input:focus" "\n")
        (check notifications => (m/embeds [{:title "nREPL Connected"}])))
      (h/goto-line! pulsar 23)

      ; FIXME: This is FAILING!
      #_
      (testing "Evaluates a top-block outside of a class"
        (h/type! pulsar "1 + 2")
        (h/run-command! pulsar "Lazuli: Evaluate Top Block"))

      (testing "evaluates a block outside a class"
        (-> pulsar
            (h/type-and-eval-resulting! "\n(1 + 2).to_s" "\"3\"")
            (check => "\"3\""))

        (-> pulsar
            (h/type-and-eval-resulting! "\n  .gsub(/3/, '20')" "\"20\"")
            (check => "\"20\"")))

      (testing "evaluates a block capturing equality if it exist"
        (h/type-and-eval! pulsar "\n\na=90\n  .to_s")
        (-> pulsar
            (h/type-and-eval-resulting! "\n\n(a + '21')" "\"9021\"")
            (check => "\"9021\"")))

      (testing "evaluates a selection"
        (h/press! pulsar :left)
        (h/press! pulsar :shift :left :left :left :left)
        (h/run-command! pulsar "Lazuli: Evaluate Selection")
        (check (h/result-with! pulsar "\"21\"") => "\"21\"")))))

(deftest watch-points
  (h/with-pulsar [pulsar :debug true]
    (async-test "can evaluate commands in watch points" {:timeout 20000 :teardown (h/reset-editor! pulsar)}
      (h/open! pulsar "main.rb")
      (h/connect-modal! pulsar)
      (h/changing [notifications (h/get-notifications! pulsar)]
        (h/type! pulsar ".modal input:focus" "\n")
        (check notifications => (m/embeds [{:title "nREPL Connected"}])))
      (h/goto-line! pulsar 23)

      (testing "calling a method creates a trace point"
        (h/type-and-eval! pulsar "\nPerson.new('Andrew', 19).hello(:japan)")
        (check (h/element-with! pulsar ".lazuli .traces span" " > /main.rb:9") => " > /main.rb:9"))

      (testing "also creates a watch point"
        (check (h/locate-all-text! pulsar ".lazuli .watches a")
               => (m/embeds [#"main.rb:9"]))))))
