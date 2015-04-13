(ns rksm.cloxp-com.simple-sender
  (:refer-clojure :exclude [send])
  (:require [rksm.cloxp-com.cloxp-client :as cloxp]
            [cljs.core.async :refer [<! >! put! close! chan pub sub]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(cloxp/start)
