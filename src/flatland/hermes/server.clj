(ns flatland.hermes.server
  (:refer-clojure :exclude [send])
  (:use compojure.core
        ring.middleware.format-params
        ring.middleware.keyword-params
        lamina.core aleph.http
        lamina.trace aleph.formats))

(def default-websocket-port
  "The port on which hermes accepts message subscriptions."
  2959)

(def default-http-port
  "The port on which hermes listens for incoming messages."
  2960)

(defn topic-listener [ch handshake]
  (receive-all ch
    (fn [topic]
      (let [events (subscribe local-trace-router topic {})]
        (siphon (map* (fn [obj]
                        (encode-json->string (assoc obj :subscription topic)))
                      events)
                ch)))))

(defn send [topic message]
  (trace* topic {:topic topic :data message}))

(defn init [{:keys [http-port websocket-port] :as config}]
  (let [websocket (start-http-server topic-listener
                                     {:port (or websocket-port default-websocket-port)
                                      :websocket true})
        http (start-http-server
              (-> (routes (PUT "/:topic" {:keys [params body-params]}
                               (send (:topic params) body-params)
                               {:status 200 :body "OK"})
                          (fn [req]
                            {:status 404}))
                  wrap-keyword-params
                  wrap-json-params
                  wrap-ring-handler)
              {:port (or http-port default-http-port)})]
    {:shutdown (fn shutdown []
                 (websocket)
                 (http))
     :config config}))
