(ns juxt.crux-ui.frontend.views.output.react-tree
  (:require ["react-ui-tree" :as ReactTree]
            [garden.core :as garden]))

(defn on-tree-change [evt]
  (println :on-tree-change evt))

(def style
  [:style
   (garden/css
     [:.react-tree
      {:min-width :100px
       :min-height :100px
       :outline "1px solid orange"}])])

(defn root []
  [:div.react-tree
   style
   [:> ReactTree
     {:paddingLeft 20
      :onChange on-tree-change
      :renderNode
      (fn [node]
        (println :render-node node)
        (.-title node))

      :tree {:title "react-ui-tree",
             :children
             [{:collapsed true,
               :title "dist",
               :children
               [{:title ":doc/id"
                 :value "eee"
                 :leaf true}]}]}}]])

