(ns flatland.hermes.main
  (:require [flatland.hermes.server :as hermes]))

(defn main [& args]
  (def server
    (hermes/init {:debug true})))
