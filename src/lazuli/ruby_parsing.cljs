(ns lazuli.ruby-parsing
  (:require [clojure.string :as str]
            [check.core :refer [check]]
            [clojure.test :as t]))

(defprotocol Ruby
  (to-clj [this]))

(defrecord RubySet [elements]
  Ruby
  (to-clj [_] (->> elements (map to-clj) set)))
(defrecord RubyMap [keyvals]
  Ruby
  (to-clj [_] (->> keyvals (map #(mapv to-clj %)) (into {}))))
(defrecord RubyVector [elements]
  Ruby
  (to-clj [_] (mapv to-clj elements)))
(defrecord RubyInstanceVar [name]
  Ruby
  (to-clj [_] (symbol name)))
(defrecord RubyNumber [num]
  Ruby
  (to-clj [_] num))
(defrecord RubyKeyword [name]
  Ruby
  (to-clj [_] (keyword name)))
(defrecord RubyString [contents]
  Ruby
  (to-clj [_] contents))
(defrecord RubyObject [name elements]
  Ruby
  (to-clj [_] (->> elements
                   (map #(mapv to-clj %))
                   (into {})
                   (tagged-literal (symbol name)))))
(defrecord RubyVariable [name]
  Ruby
  (to-clj [_] (symbol name)))
(defrecord RubyUnknownVal [value]
  Ruby
  (to-clj [_] (symbol name)))

(declare parse-ruby-string)
(defn parse-unknown [acc a-str end-of-capture]
  (let [[capture val] (re-find (re-pattern (str "^(.*?)" end-of-capture)) a-str)]
    (if capture
      [(str acc val) (subs a-str (count capture))]
      [::invalid a-str])))

(defn- parse-vector [acc a-str what]
  (case [(first a-str) what]
    ["]" :value] [(->RubyVector acc) (subs a-str 1)]
    ["]" :comma] [(->RubyVector acc) (subs a-str 1)]
    ["," :comma] (recur acc (subs a-str 2) :value)
    ["," :value] [::invalid a-str]
    (case what
      ;; We're on track - we expect a value
      :value (let [[element rest] (parse-ruby-string a-str false)]
               (recur (conj acc element) rest :comma))
      ;; We're screwed - we expected a comma
      :comma (let [last-element (peek acc)
                   correct-acc (pop acc)
                   [val rst] (parse-unknown last-element a-str ",")]
               (recur (conj correct-acc val) rst :comma)))))

(defn- parse-map [acc string what]
  (let [fst (first string)
        two (delay (subs string 0 2))
        pair (delay (parse-ruby-string string false))
        parsed (delay (first @pair))
        rst (delay (second @pair))]
    (cond
      (= ["}" :key] [fst what]) [(->RubyMap acc) (subs string 1)]
      (= ["}" :comma] [fst what]) [(->RubyMap acc) (subs string 1)]
      (= ["," :comma] [fst what]) (recur acc (subs string 2) :key)
      (= :key what) (let [[val rst2] (parse-map [] @rst :sep)]
                      (recur (conj acc [@parsed val]) rst2 :comma))
      (= ["=>" :sep] [@two what]) (recur acc (subs string 2) :value)
      (= :value what) @pair)))

(defn- parse-string [string]
  (let [[unescapeds [last]] (->> string
                                 (re-seq #"^(.*?)\"")
                                 (map second)
                                 (split-with #(str/ends-with? % "\\")))
        final-str (str/join "\"" (concat (map #(subs % 0 (-> % count dec)) unescapeds)
                                         [last]))]
    [(->RubyString final-str)
     (subs string (-> final-str count (+ (count unescapeds) 1)))]))

(defn- parse-keyword [string]
  (let [[parsed rest] (parse-ruby-string string false)]
    [(->RubyKeyword (if (instance? RubyString parsed)
                      (str ":" (pr-str (:contents parsed)))
                      (str ":" (:name parsed))))
     rest]))

(defn- parse-number [string]
  (let [captured (re-find #"[\-\d\.]+" string)]
    [(->RubyNumber (if (re-find #"\." captured)
                     (js/parseFloat captured)
                     (js/parseInt captured)))
     (subs string (count captured))]))

(defn- parse-var [string]
  (let [capture (re-find #"[^\d]@?[a-zA-Z\d\.\_]*[\?\!]?" string)]
    [(->RubyVariable capture)
     (subs string (count capture))]))

(defn parse-ruby-string
  ([a-str] (first (parse-ruby-string a-str false)))
  ([a-str accept-more?]
   (let [first-char (first a-str)
         rst (delay (subs a-str 1))]
     (case first-char
       "[" (parse-vector [] @rst :value)
       "{" (parse-map [] @rst :key)
       "\"" (parse-string @rst)
       ":" (parse-keyword @rst)
       (cond
         (re-find #"[\d\-]" first-char) (parse-number a-str)
         :else (parse-var a-str))))))

(t/deftest parsing-ruby-primitives
  (t/testing "parsing numbers"
    (check (parse-ruby-string "10") => (->RubyNumber 10))
    (check (parse-ruby-string "-10") => (->RubyNumber -10))
    (check (parse-ruby-string "10.2") => (->RubyNumber 10.2))
    (check (parse-ruby-string "-10.2") => (->RubyNumber -10.2)))

  (t/testing "parsing strings"
    (check (parse-ruby-string "\"foobar\"") => (->RubyString "foobar"))
    (check (parse-ruby-string "\"foo\\\"bar\"") => (->RubyString "foo\"bar")))

  (t/testing "parsing identifiers"
    (check (parse-ruby-string "foobar") => (->RubyVariable "foobar"))
    (check (parse-ruby-string "foobar?") => (->RubyVariable "foobar?"))
    (check (parse-ruby-string "foobar!") => (->RubyVariable "foobar!"))
    (check (parse-ruby-string "@foobar") => (->RubyVariable "@foobar"))
    (check (parse-ruby-string "@@foobar") => (->RubyVariable "@@foobar")))

  (t/testing "parsing keywords"
    (check (parse-ruby-string ":foobar") => (->RubyKeyword ":foobar"))
    (check (parse-ruby-string ":\"foobar\"") => (->RubyKeyword ":\"foobar\""))))

(t/deftest parsing-ruby-collections
  (t/testing "parsing vectors"
    (check (parse-ruby-string "[]") => (->RubyVector []))
    (check (parse-ruby-string "[1]") => (->RubyVector [(->RubyNumber 1)]))
    (check (parse-ruby-string "[1, :foo]") => (->RubyVector [(->RubyNumber 1)
                                                             (->RubyKeyword ":foo")])))

  (t/testing "parsing maps"
    (check (parse-ruby-string "{}") => (->RubyMap []))
    (check (parse-ruby-string "{:foo=>10}") => (->RubyMap [[(->RubyKeyword ":foo") (->RubyNumber 10)]]))
    (check (parse-ruby-string "{:foo=>10, :bar=>:lol}")
           => (->RubyMap [[(->RubyKeyword ":foo") (->RubyNumber 10)]
                          [(->RubyKeyword ":bar") (->RubyKeyword ":lol")]]))))

(t/deftest parsing-weird-collections
  (t/testing "when the value is unknown"
    (check (parse-ruby-string "[1, plain text, 3]")
           => (->RubyVector [(->RubyNumber 1)
                             (->RubyUnknownVal "plain text")
                             (->RubyNumber 3)]))))
