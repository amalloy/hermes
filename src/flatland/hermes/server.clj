(ns flatland.hermes.server
  (:refer-clojure :rename {send send-agent})
  (:use ring.middleware.format-params
        ring.middleware.keyword-params)
  (:require [flatland.hermes.queue :as q]
            [flatland.useful.utils :refer [returning]]
            [aleph.http :as http]
            [aleph.formats :as formats]
            [lamina.trace :as trace]
            [lamina.trace.router :as router]
            [lamina.core :as lamina :refer [siphon receive-all]]
            [lamina.cache :as cache]
            [lamina.time]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [clojure.tools.macro :refer [macrolet]]
            [compojure.core :refer [routes PUT]])
  (:import (java.text SimpleDateFormat)
           (java.util Date)))

(let [worker (agent nil)]
  (defn log* [msg & args]
    (send-agent worker (fn [_]
                         (.print System/out (.format (SimpleDateFormat. "HH:mm:ss") (Date.)))
                         (.print System/out " - ")
                         (.printf System/out msg (into-array Object args))
                         (.println System/out)))))

(defmacro log [config & args]
  `(when (:debug ~config)
     (log* ~@args)))

(def default-websocket-port
  "The port on which hermes accepts message subscriptions."
  2959)

(def default-http-port
  "The port on which hermes listens for incoming messages."
  2960)

(def default-message-retention
  "Number of milliseconds to save old messages for"
  5000)

(defn subscribe* [router topic]
  (trace/subscribe router (str "&" topic) {}))

(defn subscribe [config topic]
  (subscribe* (:router config) topic))

(defn send
  ([config topic message]
     (send config topic message nil))
  ([config topic message publisher-ip]
     (let [blob {:topic topic :data message}
           now (System/currentTimeMillis)]
       (trace/trace :hermes:publish (assoc blob :publisher publisher-ip))
       (log config "Received new message %s" (apply str (pr-str blob)
                                                    (when publisher-ip
                                                      [" from " publisher-ip])))
       (q/add-numbered-message (:recent config) blob now (- now (:retention config)))
       (let [channel (cache/get-or-create (:cache config) topic #(lamina/close %))]
         ;; is a no-op if nobody is listening to this topic
         (lamina/enqueue channel blob)))))

(defn all-recent-messages [config]
  (q/messages-with-numbers (:recent config) (- (System/currentTimeMillis)
                                               (:retention config))))

(defn glob->regex [glob]
  (re-pattern (s/replace glob "*" ".*")))

(defn replay-recent [config channel topic]
  (let [messages (all-recent-messages config)
        q (lamina.time/non-realtime-task-queue)
        router (trace/trace-router
                {:generator (fn [{:keys [pattern]}]
                              (let [regex (glob->regex pattern)]
                                (doto (lamina/channel* :description (str "replay: " pattern))
                                  #(apply lamina/enqueue %
                                          (filter (fn [[timestamp blob]]
                                                    (re-matches regex (:topic blob)))
                                                  messages))
                                  lamina/close)))
                 :task-queue q, :payload second :timestamp first})]
    (-> (subscribe* router topic)
        (siphon channel))
    (lamina.time/advance-until q (first (last messages)))))

(defn send-heartbeats [config client-ip ch]
  (let [heartbeats (subscribe config "hermes:heartbeat")]
    (siphon (->> heartbeats
                 (lamina/map* (fn [_]
                                (log config "Sending heartbeat to %s" client-ip)
                                "")))
            ch)))

(defn topic-listener [config]
  (macrolet [(log* [& args]
               `(log ~'config ~@args))]
    (fn [ch handshake]
      (let [client-ip (:remote-addr handshake)
            subscriptions (ref #{})
            outgoing (lamina/channel* :descrption (str "outgoing: " client-ip))]
        (log* "Incoming connection from %s" client-ip)
        (trace/trace :hermes:connect {:client client-ip})
        (send-heartbeats config client-ip ch)
        (lamina/on-closed ch (fn []
                               (log* "Client %s disconnected" client-ip)
                               (trace/trace :hermes:disconnect {:client client-ip})))
        (-> outgoing
            (->> (lamina/map* (fn [msg]
                                (log* "Sending %s to %s" (pr-str msg) client-ip)
                                (trace/trace :hermes:receive {:client client-ip})
                                (formats/encode-json->string msg))))
            (siphon ch))
        (-> ch
            (->> (lamina/map* formats/bytes->string) ;; some clients erroneously send binary frames
                 (lamina/remove* empty?)) ;; sometimes we get empty frames, which we'll just ignore
            (receive-all (fn [topic]
                           (log* "%s subscribed to %s" client-ip topic)
                           (trace/trace :hermes:subscribe {:client client-ip
                                                           :topic topic})
                           (let [was-subscribed (dosync (returning (contains? @subscriptions topic)
                                                          (alter subscriptions conj topic)))
                                 before-topics (lamina/channel* :description
                                                                (format "before-topics: %s=%s"
                                                                        client-ip topic))
                                 events (if was-subscribed
                                          (lamina/closed-channel)
                                          (subscribe config topic))]
                             (siphon (lamina/map* (fn [obj]
                                                    (assoc obj :subscription topic))
                                                  before-topics)
                                     outgoing)
                             (siphon events before-topics)
                             (replay-recent config before-topics topic)))))))))

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

(let [ignorable-errors ["reset by peer"
                        "Broken pipe"
                        "timed out"]]
  (defn error-channel [server-type]
    (doto (lamina/channel* :description "error-channel")
      (receive-all (fn [^Throwable e]
                     (if (and (instance? java.io.IOException e)
                              (let [message (.getMessage e)]
                                (some #(.contains message %)
                                      ignorable-errors)))
                       nil ;; ignore error
                       (log/error e (format "Error in %s server" server-type))))))))

(defn init [{:keys [heartbeat-ms http-port websocket-port message-retention] :as config}]
  (let [channel-cache (cache/channel-cache (fn [topic]
                                             (lamina/channel*
                                              :description (format "cache: %s" topic))))
        router (router/trace-router {:generator (fn [{:keys [pattern]}]
                                                  (cache/get-or-create channel-cache
                                                                       pattern nil))})
        config (assoc config
                 :recent (q/numbered-message-buffer)
                 :retention (or message-retention default-message-retention)
                 :cache channel-cache
                 :router router)
        websocket (http/start-http-server (topic-listener config)
                                          {:port (or websocket-port default-websocket-port)
                                           :websocket true
                                           :probes {:error (error-channel "websocket")}})
        http (http/start-http-server
              (-> (routes (PUT "/:topic" {:keys [params body-params remote-addr]}
                            (send config (:topic params) body-params remote-addr)
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
