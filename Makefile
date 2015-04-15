

man-build:
	java -cp cljs.jar:src clojure.main manual-build.clj

clean :
	lein cljsbuild clean
	lein clean
