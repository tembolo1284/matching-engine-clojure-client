(ns client.relay.engine
  "Engine connection and message processing for relay."
  (:require [client.protocol :as proto])
  (:import [java.net MulticastSocket DatagramPacket InetAddress InetSocketAddress
            NetworkInterface SocketTimeoutException]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private state
  (atom {:socket nil
         :running (atom false)
         :broadcast-fn nil
         :config nil}))

;; =============================================================================
;; Broadcast
;; =============================================================================

(defn set-broadcast-fn! [f]
  (swap! state assoc :broadcast-fn f))

(defn- broadcast! [msg]
  (when-let [f (:broadcast-fn @state)]
    (try
      (f msg)
      (catch Exception e
        (println "[error] Broadcast failed:" (.getMessage e))))))

;; =============================================================================
;; Multicast Connection
;; =============================================================================

(defn- join-multicast! [{:keys [multicast-group multicast-port multicast-iface]}]
  (println (format "[info] Joining multicast %s:%d" multicast-group multicast-port))
  (let [socket (MulticastSocket. (int multicast-port))
        group (InetAddress/getByName multicast-group)]
    (.setSoTimeout socket 1000)
    (.setReuseAddress socket true)
    (if multicast-iface
      (let [iface (NetworkInterface/getByName multicast-iface)]
        (.joinGroup socket (InetSocketAddress. group (int multicast-port)) iface))
      (.joinGroup socket group))
    (swap! state assoc :socket socket :group group)
    (println "[info] Joined multicast group")
    socket))

;; =============================================================================
;; Message Reading
;; =============================================================================

(defn- bytes->hex [^bytes bs]
  (apply str (map #(format "%02X " (bit-and % 0xFF)) bs)))

(defn- read-multicast-message! [^MulticastSocket socket]
  (let [buf (byte-array 1024)
        pkt (DatagramPacket. buf (alength buf))]
    (.receive socket pkt)
    (let [len (.getLength pkt)
          data (byte-array len)]
      (System/arraycopy (.getData pkt) 0 data 0 len)
      ;; DEBUG: Print raw data
      (println (format "[debug] Received %d bytes: %s" len (bytes->hex data)))
      ;; Decode the message
      (try
        (let [msg (proto/decode-output-bytes data)]
          (println (format "[debug] Decoded: %s" msg))
          msg)
        (catch Exception e
          (println "[warn] Failed to decode:" (.getMessage e))
          nil)))))

;; =============================================================================
;; Reader Loop
;; =============================================================================

(defn- start-reader! [socket filter-set]
  (let [running (:running @state)]
    (reset! running true)
    (future
      (println "[info] Reader thread started")
      (while @running
        (try
          (when-let [msg (read-multicast-message! socket)]
            (println (format "[debug] Message type: %s, filter: %s, pass: %s" 
                            (:type msg) filter-set (contains? filter-set (:type msg))))
            (when (or (nil? filter-set) (contains? filter-set (:type msg)))
              (println "[debug] Broadcasting to WebSocket clients...")
              (broadcast! msg)))
          (catch SocketTimeoutException _
            ;; Normal timeout, continue
            )
          (catch Exception e
            (when @running
              (println "[error] Reader:" (.getMessage e))))))
      (println "[info] Reader thread stopped"))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start! [{:keys [engine-transport multicast-group multicast-port message-filter] :as config}]
  (swap! state assoc :config config)
  
  (case engine-transport
    :multicast
    (let [socket (join-multicast! config)]
      (start-reader! socket message-filter))
    
    (println "[warn] Only multicast transport is supported for relay. Use -m GROUP:PORT")))

(defn stop! []
  (reset! (:running @state) false)
  (when-let [socket (:socket @state)]
    (try
      (when-let [group (:group @state)]
        (.leaveGroup ^MulticastSocket socket ^InetAddress group))
      (.close ^MulticastSocket socket)
      (catch Exception _)))
  (swap! state assoc :socket nil :group nil)
  (println "[info] Engine disconnected"))
