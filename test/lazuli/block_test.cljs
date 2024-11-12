(ns lazuli.block-test
  (:require [clojure.test :refer [deftest testing]]
            [check.core :refer [check]]
            [matcher-combinators.matchers :as m]
            [promesa.core :as p]
            [lazuli.test-helpers :as h]
            [saphire.code-treatment :as treat]))

(defn block-resolver [contents range]
  (:text/block
   ((:resolve treat/block) {} {:editor/contents {:text/contents contents
                                                 :text/range range}})))

(deftest single-lines
  (testing "checks single elements like primitives, arrays..."
    (check (block-resolver "20" [[0 0] [0 0]]) => {:text/contents "20"})
    (check (block-resolver ":foo" [[0 0] [0 0]]) => {:text/contents ":foo"})
    (check (block-resolver "\"foo\"" [[0 0] [0 0]]) => {:text/contents "\"foo\""})
    (check (block-resolver "[1, 2, 3]" [[0 0] [0 0]]) => {:text/contents "[1, 2, 3]"})
    (check (block-resolver "{a: 1, b: 2}" [[0 0] [0 0]]) => {:text/contents "{a: 1, b: 2}"})
    (check (block-resolver "foobar" [[0 0] [0 0]]) => {:text/contents "foobar"})
    (check (block-resolver "foobar(1, 2)" [[0 0] [0 0]]) => {:text/contents "foobar(1, 2)"})
    (check (block-resolver "(1, 2)" [[0 0] [0 0]]) => {:text/contents "(1, 2)"}))

  (testing "arithmetic"
    ;;FIXME - these fail!
    #_
    (check (block-resolver "1 + 2" [[0 0] [0 0]]) => {:text/contents "1 + 2"})
    #_
    (check (block-resolver "1 * 2" [[0 0] [0 0]]) => {:text/contents "1 + 2"})
    #_
    (check (block-resolver "a == 2" [[0 0] [0 0]]) => {:text/contents "a == 2"}))

  (testing "captures only the correct element in single-line multi-command situations"
    ;; FIXME: Do I EVEN WANT this?
    #_
    (check (block-resolver "foo; bar" [[0 0] [0 0]]) => {:text/contents "foo"})
    (check (block-resolver "foo; bar" [[0 6] [0 6]]) => {:text/contents "bar"}))

  (testing "captures assignments"
    (check (block-resolver "a = 10" [[0 4] [0 4]]) => {:text/contents "a = 10"})
    (check (block-resolver "a, b = 1, 10" [[0 8] [0 8]]) => {:text/contents "a, b = 1, 10"}))

  (testing "captures blocks"
    (check (block-resolver "a.map { |x| x + 1}" [[0 4] [0 4]])
           => {:text/contents "a.map { |x| x + 1}"})
    (check (block-resolver "a.map do |x| x + 1 end" [[0 4] [0 4]])
           => {:text/contents "a.map do |x| x + 1 end"})
    (check (block-resolver "b = a.map { |x| x + 1}" [[0 4] [0 4]])
           => {:text/contents "b = a.map { |x| x + 1}"})
    (check (block-resolver "b = a.map do |x| x + 1 end" [[0 4] [0 4]])
           => {:text/contents "b = a.map do |x| x + 1 end"})

    ;; FIXME: this fails
    #_
    (check (block-resolver "a.inject(0) { |x, y| x + y }" [[0 14] [0 14]])
           => {:text/contents "a.inject(0) { |x, y| x + y }"})
    ;; FIXME: this fails
    #_
    (check (block-resolver "a.inject(0) do |x, y| x + y end" [[0 14] [0 14]])
           => {:text/contents "a.inject(0) do |x, y| x + y end"})
    ;; FIXME: this fails
    #_
    (check (block-resolver "a.inject 0 do |x, y| x + y end" [[0 14] [0 14]])
           => {:text/contents "a.inject 0 do |x, y| x + y end"})
    ;; FIXME: this fails
    #_
    (check (block-resolver "a, b = 1, 10" [[0 4] [0 4]]) => {:text/contents "a, b = 1, 10"})))
