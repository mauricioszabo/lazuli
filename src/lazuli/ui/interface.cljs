(ns lazuli.ui.interface
  (:require [tango.ui.elements :as ui]
            [tango.ui.edn :as edn]
            [lazuli.ruby-parsing :as rp]
            [reagent.core :as r]
            [orbit.serializer :as serializer]))

(defn- leaf [state]
  [ui/Rows (pr-str (rp/to-clj @(:result state)))])

(declare as-html)
(defn- vector-ish [prefix state]
  (let [results (:elements @(:result state))
        children-states (map-indexed (fn [i _]
                                       [(r/cursor (:result state) [:elements i])
                                        (edn/create-child-state state i)])
                                     results)
        children (->> children-states
                      (map (fn [[cursor child-state]]
                             [as-html
                              (edn/update-state state
                                                cursor
                                                {:root? true}
                                                child-state)])))]
    [ui/Rows  prefix "[....]" (into [ui/Children] children)]))

(defn- hashmap [state]
  (let [results (:keyvals @(:result state))
        children-states (map-indexed (fn [i _]
                                       [(r/cursor (:result state) [:keyvals i 0])
                                        (r/cursor (:result state) [:keyvals i 1])
                                        (edn/create-child-state state i 0)
                                        (edn/create-child-state state i 1)])
                                     results)
        children (->> children-states
                      (map (fn [[kc vc ks vs]]
                             [ui/Cols
                              (ui/WithClass
                               ["map-key" "opened"]
                               [as-html
                                (edn/update-state state
                                                  kc
                                                  {:root? true}
                                                  ks)])
                              " => "
                              [as-html
                               (edn/update-state state
                                                 vc
                                                 {:root? true}
                                                 vs)]])))]
    [ui/Rows "{...}" (into [ui/Children] children)]))

(defn- object [state]
  (let [result @(:result state)
        results (:elements result)
        children-states (map-indexed (fn [i _]
                                       [(r/cursor (:result state) [:elements i 0])
                                        (r/cursor (:result state) [:elements i 1])
                                        (edn/create-child-state state i 0)
                                        (edn/create-child-state state i 1)])
                                     results)
        children (->> children-states
                      (map (fn [[kc vc ks vs]]
                             [ui/Cols
                              (ui/WithClass
                               ["map-key" "opened"]
                               [as-html
                                (edn/update-state state
                                                  kc
                                                  {:root? true}
                                                  ks)])
                              "="
                              [as-html
                               (edn/update-state state
                                                 vc
                                                 {:root? true}
                                                 vs)]])))]
    [ui/Rows "#<" (:name result) ">" (into [ui/Children] children)]))

(defprotocol UI
  (as-ui [this state]))

(extend-protocol UI
  rp/RubyInstanceVar (as-ui [_ state] (leaf state))
  rp/RubyNumber (as-ui [_ state] (leaf state))
  rp/RubyKeyword (as-ui [_ state] (leaf state))
  rp/RubyString (as-ui [_ state] (leaf state))
  rp/RubyVariable (as-ui [_ state] (leaf state))

  rp/RubySet (as-ui [_ state] (vector-ish "Set" state))
  rp/RubyVector (as-ui [_ state] (vector-ish "" state))

  rp/RubyMap (as-ui [_ state] (hashmap state))
  rp/RubyObject (as-ui [_ state] (object state))

  serializer/RawData
  (as-ui [self state] [ui/Text (pr-str (:data self))])

  number
  (as-ui [self state] [ui/Text (pr-str self)])

  boolean
  (as-ui [self state] [ui/Text (pr-str self)])

  string
  (as-ui [self state] [ui/Text (pr-str self)])

  nil
  (as-ui [self state] [ui/Text "nil"])

  object
  (as-ui [self state] [ui/Text (pr-str self)]))

  ; :default
  ; (as-ui [self state] [ui/Rows (pr-str self)]))


; (extend-type object
;   UI
;   (as-ui [self state] [ui/Rows (pr-str self)]))
;
; (as-ui "foo" {})
#_
(as-ui "foo" {})
(type 'foo)

(defn- as-html [state]
  (let [result (-> state :eval-result)
        result (if (contains? @result :result)
                 (r/cursor result [:result])
                 result)
        new-state (assoc state :result result)
        metadata (meta @result)]
    (if (:tango/wrapped-error metadata)
      (edn/wrapped-error new-state)
      (as-ui @result new-state))))

(set! edn/as-html as-html)

    ; (when-let [id (:orbit.patch/id metadata)]
    ;   (swap! (:patches state) assoc id result))
    ; (cond
    ;   (:orbit.ui.reflect/info metadata) (reflect new-state)
    ;   (:orbit.ui/lazy metadata) (lazy new-state)
    ;   (:orbit.shadow/error metadata) (shadow-errors new-state)
    ;   (:tango/interactive metadata) (int/interactive new-state)
    ;   (:tango/wrapped-error metadata) (wrapped-error new-state)
    ;   (:tango/generic-stacktrace metadata) (generic-stacktrace new-state)
    ;   (instance? serializer/RawData @result) (raw-data new-state)
    ;   (serializer/tagged-literal-with-meta? @result) (tagged new-state)
    ;   (map? @result) (as-map new-state)
    ;   (vector? @result) (as-vector new-state)
    ;   (set? @result) (as-set new-state)
    ;   (coll? @result) (as-list new-state)
    ;   :else (leaf new-state))))

;; FIXME - make Tango allow for this cus
#_
(defn for-result [result editor-state]
  (r/with-let [local-state (r/atom {})
               patches (atom {})
               result (if (:error result)
                        (assoc result :result ^:tango/wrapped-error [(:error result)])
                        result)
               result-a (r/atom result)
               new-eql (helpers/prepare-new-eql editor-state)
               new-editor-state (atom (update @editor-state :editor/features assoc
                                             :eql new-eql
                                             :original-eql (-> @editor-state
                                                               :editor/features
                                                               :eql)))]
    ^{:patches patches}
    [as-html {:eval-result result-a
              :editor-state new-editor-state
              :patches patches
              :state local-state
              :root? true}]))
; (elements/Rows)
