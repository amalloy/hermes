(ns flatland.hermes.server
  (:refer-clojure :exclude [send])
  (:use compojure.core
        ring.middleware.format-params
        ring.middleware.keyword-params
        lamina.core aleph.http
        lamina.trace aleph.formats)
  (:require [flatland.hermes.queue :as q]
            [lamina.trace :as trace]
            [lamina.core :as lamina]
            [clojure.string :as s]))

(def default-websocket-port
  "The port on which hermes accepts message subscriptions."
  2959)

(def default-http-port
  "The port on which hermes listens for incoming messages."
  2960)

(def recent-messages (q/numbered-message-buffer))
(def message-retention "Number of milliseconds to save old messages for"
  5000)

(defn send [topic message]
  (let [blob {:topic topic :data message}
        now (System/currentTimeMillis)]
    (q/add-numbered-message recent-messages blob now (- now message-retention))
    (trace* topic blob)))

(defn all-recent-messages []
  (q/messages-with-numbers recent-messages))

(defn glob-matches? [s glob]
  (re-matches (re-pattern (s/replace glob "*" ".*"))
              s))

(defn replay-recent [channel topic]
  (let [messages (all-recent-messages)
        q (lamina.time/non-realtime-task-queue)
        router (trace/trace-router
                {:generator (fn [{:strs [pattern]}]
                              (apply lamina/closed-channel
                                     (for [[timestamp blob] messages
                                           :when (glob-matches? (:topic blob)
                                                                pattern)]
                                       [timestamp (assoc blob :subscription topic)])))
                 :task-queue q, :payload second :timestamp first})]
    (lamina/siphon (trace/subscribe router topic {})
                   channel)
    (lamina.time/advance-until q (first (last messages)))))

(defn topic-listener [ch handshake]
  (let [outgoing (lamina/channel)]
    (-> (lamina/map* encode-json->string outgoing)
        (lamina/siphon ch))
    (receive-all ch
                 (fn [topic]
                   (let [events (subscribe local-trace-router topic {})]
                     (siphon (map* (fn [obj]
                                     (assoc obj :subscription topic))
                                   events)
                             outgoing)
                     (replay-recent outgoing topic))))))

(defn init [{:keys [http-port websocket-port] :as config}]
  (let [websocket (start-http-server topic-listener
                                     {:port (or websocket-port default-websocket-port)
                                      :websocket true})
        http (start-http-server
              (-> (routes (PUT "/:topic" {:keys [params body-params]}
                               (send (:topic params) body-params)
                               {:status 200 :body "OK\n"})
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
