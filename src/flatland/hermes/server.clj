(ns flatland.hermes.server
  (:refer-clojure :exclude [send])
  (:use compojure.core
        ring.middleware.format-params
        ring.middleware.keyword-params
        lamina.core aleph.http
        lamina.trace aleph.formats))

(defn topic-listener [ch handshake]
  (receive-all ch
    (fn [topic]
      (let [events (subscribe local-trace-router topic {})]
        (siphon (map* (fn [obj]
                        (encode-json->string (assoc obj :subscription topic)))
                      events)
                ch)))))

(defn send [key message]
  (trace* key {:topic key :data message}))

(defn -main [& args]
  (def websocket-server
    (start-http-server topic-listener {:port 8008 :websocket true}))
  (def send-server
    (start-http-server
     (-> (routes (POST "/message/:topic" {:keys [params body-params]}
                       (send (:topic params) body-params)
                       {:status 204})
                 (fn [req]
                   {:status 404}))
         wrap-keyword-params
         wrap-json-params
         wrap-ring-handler)
     {:port 8800})))
