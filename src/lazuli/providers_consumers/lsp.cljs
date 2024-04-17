(ns lazuli.providers-consumers.lsp
  (:require [promesa.core :as p]))

(defonce provider (atom nil))

(defn activate [s]
  (prn :ACT)
  (swap! provider #(or % s)))
  ; (let [div (. js/document (createElement "div"))]
  ;   (.. div -classList (add "inline-block" "lazuli"))
  ;   (reset! status-bar-tile (. ^js @status-bar
  ;                             (addRightTile #js {:item div :priority 101})))
  ;   (rdom/render [view] div)))

(defn file->uri [file]
  (.uriFromFile ^js @provider file))

(defn find-references [uri row col]
  (p/let [res (.runCommand ^js @provider
                           "Ruby"
                           "textDocument/references"
                           (clj->js
                            {:textDocument {:uri uri}
                             :position {:line row
                                        :character col}
                             :context {:includeDeclaration false}}))]
    (-> res .-result (js->clj :keywordize-keys true))))
