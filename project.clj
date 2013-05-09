(defproject org.flatland/hermes "0.1.7"
  :description "Push notifications for your browser."
  :url "http://github.com/flatland/hermes"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.macro "0.1.2"]
                 [aleph "0.3.0-rc1"]
                 [lamina "0.5.0-rc2"]
                 [compojure "1.1.1"]
                 [ring-middleware-format "0.2.4" :exclusions [ring]]]
  :jvm-opts ["-Xmx4g"]
  :main flatland.hermes)
