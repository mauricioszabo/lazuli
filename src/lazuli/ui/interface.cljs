(ns lazuli.ui.interface
  (:require [tango.ui.elements :as ui]
            [tango.ui.edn :as edn]
            [lazuli.ruby-parsing :as rp]
            [reagent.core :as r]
            [tango.integration.repl-helpers :as helpers]
            [orbit.serializer :as serializer]))

(declare as-html)

(defn- OpenClose [open? parent-elem-delay open-elem-delay closed-elem-delay]
  (let [is-open? (atom open?)]
    [ui/Link {:icon ["chevron" (if open? "opened" "closed")]
              :on-click (fn [set-icon]
                          (when closed-elem-delay (.remove @closed-elem-delay))
                          (.remove @open-elem-delay)
                          (if @is-open?
                            (do
                              (when closed-elem-delay (.appendChild @parent-elem-delay @closed-elem-delay))
                              (set-icon "chevron closed"))
                            (do
                              (.appendChild @parent-elem-delay @open-elem-delay)
                              (set-icon "chevron opened")))
                          (swap! is-open? not))}]))

(defn- String [contents state]
  (let [closed-txt (if (-> contents count (> 100))
                     (str (subs (pr-str contents) 0 100) "...")
                     (pr-str contents))
        closed (delay (ui/dom [ui/Rows closed-txt]))
        opened (delay (ui/dom [ui/Cols [ui/Icon "quote"] " " [ui/WithClass ["italic"]
                                                              [ui/Text contents]]]))
        is-open? (atom false)
        col (atom nil)]
    (reset! col (ui/dom [ui/Cols
                         (when (:root? state) [OpenClose false col opened closed])
                         @closed]))))

(defn- root-vector [elements state]
  ["["
   (->> elements
        (take 50)
        (map #(as-html % (assoc state :root? false)))
        (interpose ", ")
        (into [:<>]))
   (if (-> elements count (> 100))
     ", ...]"
     "]")])

(defn- Vector [{:keys [elements]} state]
  (let [parent (root-vector elements state)
        child (->> elements
                   (map #(as-html % state))
                   (into [ui/Children])
                   ui/dom
                   delay)
        root-elem (atom nil)]
    (reset! root-elem
            (ui/dom
             [ui/Rows
              (into
               [ui/Cols (when (:root? state) [OpenClose false root-elem child nil])]
               parent)]))))

(defn- keyval-parent [keyvals state separator]
  (->> keyvals
       (take 50)
       (map (fn [[k v]]
              [:<>
               (as-html k (assoc state :root? false))
               separator
               (as-html v (assoc state :root? false))]))
       (interpose ", ")
       (into [:<>])))

(defn- keyval-child [keyvals state]
  (->> keyvals
       (map (fn [[k v]]
              [:<>
               [ui/WithClass ["map-key" "opened"] (as-html k state)]
               (as-html v state)]))
       (interpose ui/Space)
       (into [ui/Children])
       ui/dom
       delay))

(defn- Map [keyvals state]
  (let [parent (keyval-parent keyvals state " => ")
        child (keyval-child keyvals state)
        root-elem (atom nil)]

    (doto
     (reset! root-elem
             (ui/dom
              [ui/Rows
               [ui/Cols
                (when (:root? state) [OpenClose false root-elem child nil])
                "{" parent "}"]]))
     (prn :W?))))

(defn Object [obj-name elements state]
  (let [inner-is-ruby? (implements? rp/Ruby elements)
        parent (if inner-is-ruby?
                 [as-html elements (assoc state :root? false)]
                 (keyval-parent elements state "="))
        child (if inner-is-ruby?
                (delay (ui/dom [ui/Children [as-html elements state]]))
                (keyval-child elements state))
        root-elem (atom nil)]
    (reset! root-elem
            (ui/dom
             [ui/Rows
              [ui/Cols
               (when (:root? state) [OpenClose false root-elem child nil])
               "#<" obj-name " " parent ">"]]))))

(defprotocol UI
  (as-ui [this state]))

(extend-protocol UI
  rp/RubyInstanceVar (as-ui [self _] [ui/Cols (:name self)])
  rp/RubyNumber (as-ui [self _] [ui/Cols (-> self :num str)])
  rp/RubyKeyword (as-ui [self _] [ui/Cols (:name self)])
  rp/RubyString (as-ui [self state] [String (:contents self) state])
  rp/RubyVariable (as-ui [self _] [ui/Cols (:name self)])
  ;
  ; rp/RubySet (as-ui [_ state] (vector-ish "Set" state))
  rp/RubyVector (as-ui [self state] [Vector self state])

  rp/RubyMap (as-ui [self state] [Map (:keyvals self) state])
  rp/RubyObject (as-ui [self state] [Object (:name self) (:elements self) state])
  rp/RubyUnknownVal (as-ui [self state] [ui/Cols (:value self)])

  serializer/RawData
  (as-ui [self state] [ui/Cols (pr-str (:data self))])

  number
  (as-ui [self state] [ui/Cols (pr-str self)])

  boolean
  (as-ui [self state] [ui/Cols (pr-str self)])

  string
  (as-ui [self state] [String self state])

  nil
  (as-ui [self state] [ui/Cols "nil"])

  object
  (as-ui [self state] [ui/Text (pr-str self)]))


(defn WrappedError [result state]
  [ui/WithClass ["error"] [as-html (first result) state]])

(defn- as-html [result state]
  (let [metadata (meta result)]
    (cond
      (:tango/wrapped-error metadata) [WrappedError result state]
      :else (as-ui result state))))

(defn for-result [result editor-state]
  (let [local-state (atom {})
        patches (atom {})
        result (if (:error result)
                 (assoc result :result ^:tango/wrapped-error [(:error result)])
                 result)
        result-a (atom result)
        new-eql (helpers/prepare-new-eql editor-state)
        new-editor-state (atom (update @editor-state :editor/features assoc
                                      :eql new-eql
                                      :original-eql (-> @editor-state
                                                        :editor/features
                                                        :eql)))]
    ^{:patches patches}
    [as-html (:result result)
     {:eval-result result-a
      :editor-state new-editor-state
      :patches patches
      :state local-state
      :root? true}]))

(set! edn/for-result for-result)
