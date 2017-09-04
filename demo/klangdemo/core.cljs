(ns klangdemo.core
  (:require
   [klang.core :as k :refer-macros [info! warn! erro!]]))

(k/toggle-showing! true)
(info! :may-wanna-click "on on this message" js/document.head)

(defn foo [x]
  (warn! "Hmmm "))

(foo :arg1)



