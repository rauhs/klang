
# Seems to not work:
CLJS=~/.m2/repository/org/clojure/clojurescript/0.0-3169/clojurescript-0.0-3169.jar

# Download the release from github:
CLJS_JAR=https://github.com/clojure/clojurescript/releases/download/r3208/cljs.jar

man-build:
	java -cp cljs.jar:src clojure.main manual-build.clj

prod:
	lein cljsbuild auto prod

clean :
	lein cljsbuild clean
	lein clean

print-%: ; @echo $*=$($*)

.PHONY: demo
demo: demo.html

demo.html :
	echo "<html><head></head>" > $@
	echo "<body><script>" >> $@
	cat ./resources/public/cljs/demo/app.js >> $@
	echo "</script></body></html>" >> $@

