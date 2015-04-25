(ns rksm.cloxp-com.simple-sender
  (:require [rksm.cloxp-com.cloxp-client :as cloxp]))

(cloxp/start)

(js/setTimeout
 #(set! (.. js/document -body -style -backgroundColor) "white"))
