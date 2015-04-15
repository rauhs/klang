(ns tools.helpers)

;; Some code from:
;; github.com/swannodette/

(defn index-of [xs x]
  (let [len (count xs)]
    (loop [i 0]
      (if (< i len)
        (if (= (nth xs i) x)
          i
          (recur (inc i)))
        -1))))

(defn atom? [x]
  (instance? Atom x))

(defn error? [x]
  (instance? js/Error x))

(defn throw-err [x]
  (if (error? x)
    (throw x)
    x))

