(ns lazuli.core-test
  (:require [lazuli.evaluation-test]
            [lazuli.block-test]
            [clojure.test :as test]
            [saphire.code-treatment :as treat]))


(defmethod test/report [::test/default :begin-test-var] [m]
  (println "Testing:" (test/testing-vars-str m)))

(defn run-tests [ & args]
  (when (.. js/process -env -CI)
    (defmethod test/report [::test/default :summary] [{:keys [test pass fail error]}]
      (println "Ran" test "tests containing" (+ pass fail error) "assertions.")
      (println pass "passed," fail "failures," error "errors.")
      (if (= 0 fail error)
        (js/process.exit 0)
        (js/process.exit 1)))
    ; (js/setTimeout)
    (.then treat/done
         #(test/run-all-tests #"lazuli.*\-test"))))
     ; 200)))
