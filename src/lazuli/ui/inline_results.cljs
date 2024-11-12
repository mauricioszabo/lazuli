(ns lazuli.ui.inline-results
  (:require [reagent.dom :as rdom]
            [promesa.core :as p]
            ["atom" :refer [TextEditor]]
            [tango.ui.elements :as ui]))

(defonce ^:private results (atom {}))

(defn- update-marker [id, ^js marker, ^js dec]
  (when-not (.isValid marker)
    (let [div (get-in @results [id :div])]
      (try (rdom/unmount-component-at-node div) (catch :default _)))
    (swap! results dissoc id)
    (.destroy dec)
    (.destroy marker)))

(defn- create-result [id editor range]
  (let [marker ^js (. editor markBufferRange
                     (clj->js range)
                     #js {:invalidate "inside"})
        div (. js/document createElement "div")
        dec (. ^js editor decorateMarker marker #js {:type "block" :position "after" :item div})]
    (.onDidChange marker (fn [_] (update-marker id marker dec)))
    (.onDidDestroy marker (fn [_] (update-marker id marker dec)))
    (swap! results assoc id {:marker marker :div div :editor editor})
    (aset marker "__divElement" div)
    div))

(defn- find-result [^js editor range]
  (some-> editor
          (.findMarkers #js {:endBufferRow (-> range last first)})
          (->> (filter #(.-__divElement ^js %))
               first)))

(defn create! [data]
  (when-let [editor (-> data :editor/data :editor)]
    (let [id (:id data)
          range (:text/range data)
          _ (when-let [old-marker (find-result editor range)]
              (.destroy old-marker))
          div (create-result id editor range)]
      (doto div
            (aset "classList" "lazuli result native-key-bindings")
            (aset "innerHTML" "<div class='tango icon-container'><span class='icon loading'></span></div>")))))

(defn update! [connection-state data]
  (let [id (:id data)]
    (when-let [{:keys [div]} (get @results id)]
      (let [parse (-> @connection-state :editor/features :result-for-renderer)
            hiccup (parse data connection-state)]
        (swap! results assoc-in [id :parsed] hiccup)
        (set! (.-innerHTML div) "")
        (.appendChild div (ui/dom hiccup))))))

(defn all-parsed-results []
  (for [[_ {:keys [div]}] @results
        :when div]
    div))

(defn clear-results! [curr-editor]
  (doseq [[_ {:keys [editor marker]}] @results
          :when (= (.-id curr-editor) (.-id editor))]
    (.destroy ^js marker)))
