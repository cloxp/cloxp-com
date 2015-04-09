(ns rksm.cloxp-com.eval
  (:require [rksm.cloxp-com.messenger :as m]))

(defn eval-js
  [code]
  (try
    {:status :success :value (js* "eval(~{code})")}
    (catch :default e
      {:status :exception :value (pr-str e)
       :stacktrace (if (.hasOwnProperty e "stack")
                     (.-stack e)
                     "No stacktrace available.")})))

(defn eval-js-service
  [receiver {{:keys [code]} :data, :as msg}]
  (m/answer receiver msg (eval-js code) false))
