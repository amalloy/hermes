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

(defn log [config & args]
  (when (:debug config)
    (.println System/out (apply format args))))

(def default-websocket-port
  "The port on which hermes accepts message subscriptions."
  2959)

(def default-http-port
  "The port on which hermes listens for incoming messages."
  2960)

(def default-message-retention
  "Number of milliseconds to save old messages for"
  5000)

(defn send [config topic message]
  (let [blob {:topic topic :data message}
        now (System/currentTimeMillis)]
    (log config "Received new message %s" (pr-str blob))
    (q/add-numbered-message (:recent config) blob now (- now (:retention config)))
    (trace* topic blob)))

(defn all-recent-messages [config]
  (q/messages-with-numbers (:recent config)))

(defn glob->regex [glob]
  (re-pattern (s/replace glob "*" ".*")))

(defn replay-recent [config channel topic]
  (let [messages (all-recent-messages config)
        q (lamina.time/non-realtime-task-queue)
        router (trace/trace-router
                {:generator (fn [{:strs [pattern]}]
                              (let [regex (glob->regex pattern)]
                                (apply lamina/closed-channel
                                       (for [[timestamp blob] messages
                                             :when (re-matches regex (:topic blob))]
                                         [timestamp blob]))))
                 :task-queue q, :payload second :timestamp first})]
    (lamina/siphon (trace/subscribe router topic {})
                   channel)
    (lamina.time/advance-until q (first (last messages)))))

(defn topic-listener [config]
  (fn [ch handshake]
    (let [client-ip (:remote-addr handshake)]
      (log config "Incoming connection from %s" client-ip)
      (let [outgoing (lamina/channel)]
        (-> (lamina/map* encode-json->string outgoing)
            (lamina/siphon ch))
        (lamina/receive-all outgoing
                            (fn [msg]
                              (log config "Sending %s to %s" (pr-str msg) client-ip)))
        (receive-all ch
                     (fn [topic]
                       (let [before-topics (lamina/channel)
                             events (subscribe local-trace-router topic {})]
                         (siphon (map* (fn [obj]
                                         (assoc obj :subscription topic))
                                       before-topics)
                                 outgoing)
                         (siphon events before-topics)
                         (replay-recent config before-topics topic))))))))

(defn init [{:keys [http-port websocket-port message-retention] :as config}]
  (let [config (assoc config
                 :recent (q/numbered-message-buffer)
                 :retention (or message-retention default-message-retention))
        websocket (start-http-server (topic-listener config)
                                     {:port (or websocket-port default-websocket-port)
                                      :websocket true})
        http (start-http-server
              (-> (routes (PUT "/:topic" {:keys [params body-params]}
                               (send config (:topic params) body-params)
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
