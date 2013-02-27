(ns flatland.hermes.queue)

(defn pop-while [pred q]
  (cond (empty? q) q
        (pred (peek q)) (recur pred (pop q))
        :else q))

(defn message-buffer []
  (atom (delay clojure.lang.PersistentQueue/EMPTY)))

(defn add-message [buffer message pred]
  (swap! buffer
         (fn [queue]
           (let [cleaned-queue @queue]
             (delay (pop-while pred (conj cleaned-queue message)))))))

(defn current-messages [buffer]
  @@buffer)

(defn numbered-message-buffer []
  (message-buffer))

(defn add-numbered-message [buffer message current-num min-to-keep]
  (add-message buffer [current-num message] #(< (first %) min-to-keep)))

(defn messages-with-numbers [buffer]
  (current-messages buffer))

(defn messages-without-numbers [buffer]
  (map second (current-messages buffer)))
