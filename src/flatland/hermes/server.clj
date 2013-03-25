(ns flatland.hermes.server
  (:refer-clojure :exclude [send])
  (:use ring.middleware.format-params
        ring.middleware.keyword-params)
  (:require [flatland.hermes.queue :as q]
            [flatland.useful.utils :refer [returning]]
            [aleph.http :as http]
            [aleph.formats :as formats]
            [lamina.trace :as trace]
            [lamina.core :as lamina :refer [siphon receive-all]]
            [lamina.time]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [compojure.core :refer [routes PUT]])
  (:import (java.text SimpleDateFormat)
           (java.util Date)))

(defn log [config & args]
  (when (:debug config)
    (.println System/out (format "%s - %s"
                                 (.format (SimpleDateFormat. "HH:mm:ss") (Date.))
                                 (apply format args)))))

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
    (trace/trace* topic blob)))

(defn all-recent-messages [config]
  (q/messages-with-numbers (:recent config) (- (System/currentTimeMillis)
                                               (:retention config))))

(defn glob->regex [glob]
  (re-pattern (s/replace glob "*" ".*")))

(defn replay-recent [config channel topic]
  (let [messages (all-recent-messages config)
        q (lamina.time/non-realtime-task-queue)
        router (trace/trace-router
                {:generator (fn [{:strs [pattern]}]
                              (let [regex (glob->regex pattern)]
                                (apply lamina/closed-channel
                                       (filter (fn [[timestamp blob]]
                                                 (re-matches regex (:topic blob)))
                                               messages))))
                 :task-queue q, :payload second :timestamp first})]
    (-> (trace/subscribe router topic {})
        (siphon channel))
    (lamina.time/advance-until q (first (last messages)))))

(defn send-heartbeats [log client-ip ch]
  (let [heartbeats (trace/subscribe trace/local-trace-router "hermes:heartbeat" {})]
    (siphon (->> heartbeats
                 (lamina/map* (fn [_]
                                (log "Sending heartbeat to %s" client-ip)
                                "")))
            ch)))

(defn topic-listener [config]
  (fn [ch handshake]
    (let [client-ip (:remote-addr handshake)
          subscriptions (ref #{})
          log (partial log config)
          outgoing (lamina/channel)]
      (log "Incoming connection from %s" client-ip)
      (send-heartbeats log client-ip ch)
      (lamina/on-closed ch #(log "Client %s disconnected" client-ip))
      (-> outgoing
          (->> (lamina/map* (fn [msg]
                              (log "Sending %s to %s" (pr-str msg) client-ip)
                              (formats/encode-json->string msg))))
          (siphon ch))
      (-> ch
          (->> (lamina/map* formats/bytes->string) ;; some clients erroneously send binary frames
               (lamina/remove* empty?)) ;; sometimes we get empty frames, which we'll just ignore
          (receive-all (fn [topic]
                         (log "%s subscribed to %s" client-ip topic)
                         (let [was-subscribed (dosync (returning (contains? @subscriptions topic)
                                                        (alter subscriptions conj topic)))
                               before-topics (lamina/channel)
                               events (if was-subscribed
                                        (lamina/closed-channel)
                                        (trace/subscribe trace/local-trace-router topic {}))]
                           (siphon (lamina/map* (fn [obj]
                                                  (assoc obj :subscription topic))
                                                before-topics)
                                   outgoing)
                           (siphon events before-topics)
                           (replay-recent config before-topics topic))))))))

(defn heartbeat-worker [{:keys [heartbeat heartbeat-ms] :or {heartbeat true,
                                                             heartbeat-ms (* 60 1000)}
                         :as config}]
  (if-not heartbeat
    (constantly true)
    (let [worker (future
                   (while true
                     (Thread/sleep heartbeat-ms)
                     (send config "hermes:heartbeat" {:alive true})))]
      (fn cancel []
        (future-cancel worker)))))

(defn error-channel [server-type]
  (doto (lamina/channel)
    (receive-all (fn [^Throwable e]
                   (if (and (instance? java.io.IOException e)
                            (-> (.getMessage e)
                                (.contains "reset by peer")))
                     nil ;; ignore error
                     (log/error e (format "Error in %s server" server-type)))))))

(defn init [{:keys [heartbeat-ms http-port websocket-port message-retention] :as config}]
  (let [config (assoc config
                 :recent (q/numbered-message-buffer)
                 :retention (or message-retention default-message-retention))
        websocket (http/start-http-server (topic-listener config)
                                          {:port (or websocket-port default-websocket-port)
                                           :websocket true
                                           :probes {:error (error-channel "websocket")}})
        http (http/start-http-server
              (-> (routes (PUT "/:topic" {:keys [params body-params]}
                            (send config (:topic params) body-params)
                            {:status 200 :body "OK\n"})
                          (fn [req]
                            {:status 404}))
                  wrap-keyword-params
                  wrap-json-params
                  http/wrap-ring-handler)
              {:port (or http-port default-http-port)
               :probes {:error (error-channel "http")}})
        heartbeat (heartbeat-worker config)]
    {:shutdown (fn shutdown []
                 (heartbeat)
                 (websocket)
                 (http))
     :config config}))
