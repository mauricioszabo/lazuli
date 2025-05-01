(ns lazuli.ui.atom
  (:require ["atom" :refer [CompositeDisposable]]))

(defn warn [title text]
  (.. js/atom -notifications (addWarning title #js {:detail text})))

(defn error [title text]
  (.. js/atom -notifications (addError title #js {:detail text})))

(defn info [title text]
  (.. js/atom -notifications (addInfo title #js {:detail text})))

(defn current-editor []
  (.. js/atom -workspace getActiveTextEditor))

(def subscriptions (atom (CompositeDisposable.)))

(defn reload-subscriptions! []
  (reset! subscriptions (CompositeDisposable.)))

(def ^:private state (atom nil))

(defn save-focus! [elem]
  (when (-> @state :last-focus nil?)
    (swap! state assoc :last-focus
           (some-> js/atom .-workspace .getActiveTextEditor .-element)))
  (js/setTimeout #(.focus (.querySelector elem "input")) 100))

(defn refocus! []
  (when-let [elem (:last-focus @state)]
    (.focus elem)
    (swap! state dissoc :last-focus)))

(def aux #js {:reload reload-subscriptions!
              :get_disposable (fn [] @subscriptions)})
