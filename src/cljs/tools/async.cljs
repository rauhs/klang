(ns tools.async
  (:require-macros
    [cljs.core.async.macros :refer [go alt!]])
  (:require 
    [cljs.core.async :refer [>! <! chan put! close! timeout]]))

;; Let's think about error handling and core.async:
;; A few ideas:
;; - Create go-err macro and return two chan?
;; - Create (chan) with ex-handler that turns result/err into :ok & :err?

#_(defn throw-err [e]
  (when (instance? Throwable e) (throw e))
  e)
 
(defn throttle*
  ([in msecs]
    (throttle* in msecs (chan)))
  ([in msecs out]
    (throttle* in msecs out (chan)))
  ([in msecs out control]
    (go
      (loop [state ::init last nil cs [in control]]
        (let [[_ _ sync] cs]
          (let [[v sc] (alts! cs)]
            (condp = sc
              in (condp = state
                   ::init (do (>! out v)
                            (>! out [::throttle v])
                            (recur ::throttling last
                              (conj cs (timeout msecs))))
                   ::throttling (do (>! out v)
                                  (recur state v cs)))
              sync (if last 
                     (do (>! out [::throttle last])
                       (recur state nil
                         (conj (pop cs) (timeout msecs))))
                     (recur ::init last (pop cs)))
              control (recur ::init nil
                        (if (= (count cs) 3)
                          (pop cs)
                          cs)))))))
    out))


(defn throttle
  ([in msecs] (throttle in msecs (chan)))
  ([in msecs out]
    (->> (throttle* in msecs out)
      (filter #(and (vector? %) (= (first %) ::throttle)))
      (map second))))


(defn debounce
  ([source msecs]
    (debounce (chan) source msecs))
  ([out source msecs]
    (go
      (loop [state ::init cs [source]]
        (let [[_ threshold] cs]
          (let [[v sc] (alts! cs)]
            (condp = sc
              source (condp = state
                       ::init
                         (do (>! out v)
                           (recur ::debouncing
                             (conj cs (timeout msecs))))
                       ::debouncing
                         (recur state
                           (conj (pop cs) (timeout msecs))))
              threshold (recur ::init (pop cs)))))))
    out))

