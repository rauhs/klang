# Klang - A Clojurescript logging libary/viewer

This library offers a simple logging interface in clojurescript for
usage in the browser. It allows for powerful (user defined) log
filtering and syntax highlighting for clojure data structures:

![Example](https://github.com/rauhs/klang/blob/master/docs/img/example.png)

# Features

* Define multiple tabs that filter only the messages that you're
  interested in.
* Enter a search term in each tab that filter
* Pausing the UI in case of many logs arriving. This will not discard
  the logs but buffer them.
* Cloning existing tabs to quickly apply different search terms.

# Usage





# Use cases
The library can be used for different use cases.

## Web browser app development
This is likely the most common use case. You're developing a
Clojurescript app for the browser. You want power


### With deployment to clients
In most use cases for web app development you'll want to remove
logging data and the overhead of Klang from your JS code for
deployment.
In this case you'll need to use a few macros to 

This library /could/ offer this functionality but I think that most
developers will have a slight different opinion on what to do with
their logs.
This is why I've chosen to give this recipe that only has a few lines
of code and everybody can adapt it to their needs:

```clj

```


## Server mode
In this use case you're only interested in viewing logs in a browser
window but the browser window itself does not host a client app that
is interested in logging.

For instance, you're forwarding all your logs from your clojure
backend app to Klang. You may also have multiple backends or even your
browser app to also forward the logging to one klang instance.

This mean that you'll have a central location (the browser app running
klang) where you display your client and server-side logging data in
realtime.

## License

Copyright &copy; 2015 Andre Rauh. Distributed under the
[Eclipse Public License][], the same as Clojure.


[Eclipse Public License]: <https://raw2.github.com/rauhs/klang/master/LICENSE>
