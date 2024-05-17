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
  (to-clj [_] (keyword (str/replace-first name #":" ""))))
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
(defn parse-unknown [a-str end-of-capture]
  (let [[capture val] (re-find (re-pattern (str "^(.*?)(?=" end-of-capture ")")) a-str)]
    (if capture
      [(->RubyUnknownVal val) (subs a-str (count capture))]
      [::invalid a-str])))

(defn- protect-result [[result rst]])

(defn- parse-vector [acc a-str what]
  (case [(first a-str) what]
    ["]" :value] [(->RubyVector acc) (subs a-str 1)]
    ["]" :comma] [(->RubyVector acc) (subs a-str 1)]
    ["," :comma] (if (-> a-str second (= " "))
                   (parse-vector acc (subs a-str 2) :value)
                   [::invalid])
    ["," :value] [::invalid]
    (case what
      ;; We're on track - we expect a value
      :value (let [[element rest] (parse-ruby-string a-str false)
                   result (parse-vector (conj acc element) rest :comma)]
               (if (-> result first (= ::invalid))
                 (let [[new-element new-rest] (parse-unknown a-str "(, |\\])")]
                   (parse-vector (conj acc new-element) new-rest :comma))
                 result))
      :comma [::invalid])))

(defn- parse-map [acc a-str what]
  (let [fst (first a-str)
        two (delay (subs a-str 0 2))
        pair (delay (parse-ruby-string a-str false))
        parsed (delay (first @pair))
        rst (delay (second @pair))]
    (cond
      (= ["}" :value] [fst what]) [(->RubyMap acc) (subs a-str 1)]
      (= ["}" :comma] [fst what]) [(->RubyMap acc) (subs a-str 1)]
      (= [", " :comma] [@two what]) (parse-map acc (subs a-str 2) :value)
      (= :comma what) [::invalid]

      :searching-for-key-value
      (let [key @parsed]
        (if (-> @rst (subs 0 2) (= "=>"))
          (let [[val rst2] (parse-ruby-string (subs @rst 2) false)
                res (parse-map (conj acc [key val]) rst2 :comma)]
            (if (= [::invalid] res)
              (let [[val rst] (parse-unknown (subs @rst 2) "(, |\\})")]
                (parse-map (conj acc [key val]) rst :comma))
              res)))))))

(defn- parse-string [string]
  (let [[unescapeds [last]] (->> string
                                 (re-seq #"^(.*?)\"")
                                 (map second)
                                 (split-with #(str/ends-with? % "\\")))
        final-str (str/join "\\\"" (concat (map #(subs % 0 (-> % count dec)) unescapeds) [last]))]
    [(->RubyString (js/JSON.parse (str "\"" final-str "\"")))
     (subs string (-> final-str count inc))]))

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
  (let [capture (re-find #"^[^\d]@?(?:[a-zA-Z\d\.\_]|::)*[\?\!]?" string)]
    [(->RubyVariable capture)
     (subs string (count capture))]))

(defn- parse-inner-object [a-str]
  (loop [a-str a-str
         acc []]
    (if (-> a-str first (= ">"))
      [acc (subs a-str 1)]
      (let [[inner rst] (parse-ruby-string a-str true)
            rst (str/triml rst)]
        (case (first rst)
          (":" "=") ;; It's a key!
          (let [rst (-> rst (subs 1) str/triml)
                [value rst2] (parse-ruby-string rst true)
                rst2 (str/triml rst2)
                valid-thing-incoming #"^(,?\s*[^\s]+[=:]|\>)"]

            (if (re-find valid-thing-incoming rst2)
              (recur (str/replace-first rst2 #"^,?\s*" "") (conj acc [inner value]))
              (let [[value rst2] (parse-unknown rst "( [^\\s]+=|\\>)")]
                (recur (str/replace-first rst2 #"^,?\s*" "") (conj acc [inner value])))))
          ">" ;; It's not, so let's just return the actual parsed object
          [inner (subs rst 1)])))))

(defn- parse-object [a-str]
  (let [capture (re-find #"^[^\> ]+" a-str)
        rst (str/triml (subs a-str (count capture)))

        [inners next-chunk]
        (case (first rst)
          ">" [[] (subs rst 1)]
          (parse-inner-object rst))]
    [(->RubyObject capture inners) next-chunk]))

(defn parse-ruby-string
  ([a-str]
   (try
     (let [[parsed rest] (parse-ruby-string a-str false)]
       (if (seq rest)
         (symbol a-str)
         parsed))
     (catch :default _
       (symbol a-str))))
  ([a-str accept-more?]
   (let [first-char (first a-str)
         rst (delay (subs a-str 1))]
     (case first-char
       "[" (parse-vector [] @rst :value)
       "{" (parse-map [] @rst :value)
       "\"" (parse-string @rst)
       ":" (parse-keyword @rst)
       "#" (parse-object (subs a-str 2))
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
    (check (parse-ruby-string "@@foobar") => (->RubyVariable "@@foobar"))
    (check (parse-ruby-string "Some::Class") => (->RubyVariable "Some::Class")))

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

(t/deftest arbitrary-ruby-objects
  (check (parse-ruby-string "#<Class:0x00007fd756bc7d18>")
         => (->RubyObject "Class:0x00007fd756bc7d18" []))
  (check (parse-ruby-string "#<Lol::Foo:0x00007f218fe55440 @lol=10, @bar=20>")
         => (->RubyObject "Lol::Foo:0x00007f218fe55440" [[(->RubyVariable "@lol")
                                                          (->RubyNumber 10)]
                                                         [(->RubyVariable "@bar")
                                                          (->RubyNumber 20)]]))

  (check (parse-ruby-string "#<AR::CollProxy [#<Blah::Blah id: \"f02\", created_at: \"2023-04-03 16:18:05.813510000 +0000\">]>")
         => (->RubyObject "AR::CollProxy"
                          (->RubyVector [(->RubyObject "Blah::Blah"
                                                       [[(->RubyVariable "id") (->RubyString "f02")]
                                                        [(->RubyVariable "created_at") (->RubyString "2023-04-03 16:18:05.813510000 +0000")]])])))
  (check (parse-ruby-string "#<Some::Obj:0x00007f6b09278848 @birth=Sat, 12 Oct 1982 @end=Sun, 31 Mar 2024>")
         => (->RubyObject "Some::Obj:0x00007f6b09278848"
                          [[(->RubyVariable "@birth") (->RubyUnknownVal "Sat, 12 Oct 1982")]
                           [(->RubyVariable "@end") (->RubyUnknownVal "Sun, 31 Mar 2024")]]))

  (check (parse-ruby-string "#<Dataset: \"SELECT \\\"one\\\" FROM \\\"foo\\\"\">")
         => (->RubyObject "Dataset:"
                          (->RubyString "SELECT \"one\" FROM \"foo\""))))


(t/deftest parsing-weird-collections
  (t/testing "when the value is unknown"
    (check (parse-ruby-string "[1, plain text, 3]")
           => (->RubyVector [(->RubyNumber 1)
                             (->RubyUnknownVal "plain text")
                             (->RubyNumber 3)]))

    (check (parse-ruby-string "{1=>Hello world, 2=>3}")
           => (->RubyMap [[(->RubyNumber 1) (->RubyUnknownVal "Hello world")]
                          [(->RubyNumber 2) (->RubyNumber 3)]])))

  (t/testing "when we have a double comma"
    (check (parse-ruby-string "[1, a,, 3]")
     => (->RubyVector [(->RubyNumber 1)
                       (->RubyUnknownVal "a,")
                       (->RubyNumber 3)]))))
