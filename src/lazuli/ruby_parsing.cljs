(ns lazuli.ruby-parsing
  (:require [clojure.string :as str]
            [instaparse.core :as insta]))

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

(def ruby-result
  (insta/parser
    "
<ruby> = (var | set | map | vector | instance | number | keyword | string | object) / unknown
set = <'#<Set: {'> ruby? (<sep> ruby)* <'}>'>
map = <'{'> keyval? (<sep> keyval)* <'}'>
keyval = ruby <'=>'> ruby
vector = <'['> ruby? (<sep> ruby)* <']'>
<sep> = ',' ' '?
instance = '@' '@'? validvar
number = #'[\\d_\\.]+'
keyword = ':' (validvar | string)
object = <'#<'> validvar objectkeyval? (<','?> <' '> objectkeyval)* <'>'>
objectkeyval = ruby <('=' | ':')> ruby
var = validvar
<validvar> = #'[a-zA-Z_][\\w\\d_:]*'
string = ('\"' #'[^\"]+' '\"')
unknown = #'.' +
"))

(def ^:private ruby-elements "
<ruby> = (var | map | vector | instance | number | keyword | string | object | set) / unknown
set = <'#<Set: {'> unknown? <'}>'>
map = <'{'> unknown? <'}'>
vector = <'['> unknown? <']'>
instance = '@' '@'? validvar
number = #'[\\d_\\.]+'
keyword = ':' (validvar | string)
object = <'#<'> validvar (<' '> unknown)? <'>'>
var = validvar
<validvar> = #'[a-zA-Z_][\\w\\d_:]*'
string = ('\"' #'[^\"]+' '\"')
<sep> = ',' ' '?
unknown = #'.' +
")

(def ruby-parent (insta/parser ruby-elements))

(def ruby-inner-elements
  (insta/parser (str "inner-elems = ruby? (<sep> ruby)*\n" ruby-elements)))

(def ruby-map-elements
  (insta/parser (str "
inner-elems = keyval? (<sep> keyval)*
<keyval> = ruby? <'=>'> <' '*> ruby?
" ruby-elements)))

(def ruby-obj-elements
  (insta/parser (str "
inner-elems = objectkeyval? (<','> objectkeyval)*
<objectkeyval> = unknown <(':' | '=' )> unknown
" ruby-elements)))

(defmulti parse-ruby first)

(defmethod parse-ruby :number [[_ number]]
  (let [norm (str/replace number #"_" "")]
    (cond
      (re-find #"\." norm) (->RubyNumber (js/parseFloat norm))
      :int (->RubyNumber (js/parseInt norm)))))

(defmethod parse-ruby :string [[_ _ contents _]]
  (->RubyString contents))

(defmethod parse-ruby :var [[_ contents]]
  (->RubyVariable contents))

(defmethod parse-ruby :keyword [[_ _ contents]]
  (if (string? contents)
    (->RubyKeyword contents)
    (->RubyKeyword (-> contents parse-ruby :contents))))

(defmethod parse-ruby :map [[_ [_ & inner]]]
  (let [inner-str (apply str inner)
        [_ & vals] (ruby-map-elements inner-str)]
    (->> vals
         (partition 2)
         (mapv #(mapv parse-ruby %))
         (->RubyMap))))

(defn- ->inners [inner]
  (let [inner-str (apply str inner)
        [_ & vals] (ruby-inner-elements inner-str)]
    (mapv parse-ruby vals)))

(defmethod parse-ruby :vector [[_ [_ & inner]]]
  (->RubyVector (->inners inner)))

(defmethod parse-ruby :set [[_ [_ & inner]]]
  (->RubySet (->inners inner)))

(defmethod parse-ruby :unknown [[_ & vals]]
  (->RubyVariable (apply str vals)))

(declare parse-ruby-res)
(defmethod parse-ruby :object [[_ name [_ & inner]]]
  (let [inner-str (apply str inner)
        [_ & vals] (ruby-obj-elements inner-str)]
    (->> vals
         (partition 2)
         (mapv (fn [inner]
                 (mapv (fn [[_ & rst]]
                         (let [string-v (cond-> rst (-> rst first (= " ")) rest)]
                           (parse-ruby-res (apply str string-v))))
                       inner)))
         (->RubyObject name))))

(defmethod parse-ruby :instance [[_ at1 at2 name]]
  (->RubyInstanceVar (str at1 at2 name)))

(defn parse-ruby-res [a-string]
  (-> a-string ruby-parent first parse-ruby))


#_
(ruby-parent "[1]")

#_
(parse-ruby-res "[1]")


#_
(parse-ruby-res
 "#<Organization id: \"59fd9e8a-2a5d-469d-b5b2-b8e20ad3b2b9\", name: \"Neon\", id: 10>")

#_
(parse-ruby-res
 "#<Organization id: \"59fd9e8a-2a5d-469d-b5b2-b8e20ad3b2b9\">")

#_
(ruby-parent "#<Organization id: \"59fd9e8a-2a5d-469d-b5b2-b8e20ad3b2b9\", name: \"Neon\", created_at: \"2024-03-05 18:03:21.275150000 +0000\">")
