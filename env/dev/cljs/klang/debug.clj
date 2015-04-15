(ns klang.debug)

(defn ^:private inspect-1 [expr]
  `(let [result# ~expr]
     (js/console.info (str (pr-str '~expr) " => " (pr-str result#)))
     result#))

;; Example:
(defmacro inspect [& exprs]
  `(do ~@(map inspect-1 exprs)))


(defmacro when-debug [& exprs]
  `(when ~(with-meta 'js/goog.DEBUG assoc :tag 'boolean)
     ~@exprs))

;; -------------------------------------------------------------------
;; BREAKPOINT macro
;; (use to stop the program at a certain point,
;; then resume with the browser's debugger controls)
;; NOTE: only works when browser debugger tab is open

(defmacro breakpoint []
  '(do (js* "debugger;")
       nil)) 

