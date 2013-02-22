(defproject org.flatland/hermes "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [aleph "0.3.0-SNAPSHOT"]
                 [compojure "1.1.1"]
                 [ring-middleware-format "0.2.4" :exclusions [ring]]]
  :main flatland.hermes.server)
