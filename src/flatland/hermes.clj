(ns flatland.hermes
  (:gen-class))

(defn -main [& args]
  (require '[flatland.hermes.main])
  (apply (resolve 'flatland.hermes.main/main) args))
