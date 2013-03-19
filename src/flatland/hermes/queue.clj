(ns flatland.hermes.queue)

(defn pop-while [pred q]
  (cond (empty? q) q
        (pred (peek q)) (recur pred (pop q))
        :else q))

(defn message-buffer []
  (atom (delay clojure.lang.PersistentQueue/EMPTY)))

(defn add-messages [buffer messages pred]
  @(swap! buffer
          (fn [queue]
            (let [cleaned-queue @queue]
              (delay (pop-while pred (into cleaned-queue messages)))))))

(defn add-message [buffer message pred]
  (add-messages buffer [message] pred))

(defn update-buffer [buffer pred]
  (add-messages buffer [] pred))

(defn current-messages [buffer]
  @@buffer)

(defn numbered-message-buffer []
  (message-buffer))

(defn- keep-pred [min-to-keep]
  #(< (first %) min-to-keep))

(defn add-numbered-message [buffer message current-num min-to-keep]
  (add-message buffer [current-num message] (keep-pred min-to-keep)))

(defn messages-with-numbers
  ([buffer]
     (current-messages buffer))
  ([buffer min-to-keep]
     (update-buffer buffer (keep-pred min-to-keep))))

(defn messages-without-numbers
  ([buffer]
     (map second (messages-with-numbers buffer)))
  ([buffer min-to-keep]
     (map second (messages-with-numbers buffer (keep-pred min-to-keep)))))
