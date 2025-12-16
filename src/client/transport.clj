(ns me-client.transport
  "Transport layer for TCP, UDP, and multicast connections.
   
   Provides a unified interface for connecting to the matching engine
   and receiving market data. Used by both REPL client and relay."
  (:require [me-client.protocol :as proto])
  (:import [java.net Socket DatagramSocket DatagramPacket
            InetAddress InetSocketAddress MulticastSocket NetworkInterface]
           [java.io BufferedInputStream BufferedOutputStream]))

;; =============================================================================
;; Protocol
;; =============================================================================

(defprotocol Transport
  "Unified transport interface."
  (send-msg! [this msg] "Send an encoded message")
  (recv-msg! [this] "Receive and decode one message, nil on EOF/timeout")
  (close! [this] "Close the connection")
  (connected? [this] "Check if connection is alive"))

;; =============================================================================
;; TCP Transport
;; =============================================================================

(defrecord TcpTransport [^Socket socket ^BufferedInputStream in ^BufferedOutputStream out]
  Transport
  (send-msg! [_ msg]
    (let [bs (proto/encode-message msg)]
      (.write out bs)
      (.flush out)))
  
  (recv-msg! [_]
    (try
      (proto/read-frame in)
      (catch java.io.IOException _
        nil)))
  
  (close! [_]
    (try (.close socket) (catch Exception _)))
  
  (connected? [_]
    (and (not (.isClosed socket))
         (.isConnected socket))))

(defn tcp-connect
  "Establish TCP connection to matching engine."
  [host port & {:keys [timeout] :or {timeout 5000}}]
  (let [socket (Socket.)]
    (.connect socket (InetSocketAddress. ^String host ^int port) timeout)
    (.setSoTimeout socket timeout)
    (.setTcpNoDelay socket true)
    (->TcpTransport
     socket
     (BufferedInputStream. (.getInputStream socket))
     (BufferedOutputStream. (.getOutputStream socket)))))

;; =============================================================================
;; UDP Transport
;; =============================================================================

(defrecord UdpTransport [^DatagramSocket socket ^InetAddress addr ^int port
                         ^bytes recv-buf]
  Transport
  (send-msg! [_ msg]
    (let [bs (proto/encode-message msg)
          pkt (DatagramPacket. bs (alength bs) addr port)]
      (.send socket pkt)))
  
  (recv-msg! [_]
    (try
      (let [pkt (DatagramPacket. recv-buf (alength recv-buf))]
        (.receive socket pkt)
        (let [data (byte-array (.getLength pkt))]
          (System/arraycopy (.getData pkt) 0 data 0 (.getLength pkt))
          ;; Skip 4-byte header, decode payload
          (when (>= (alength data) 4)
            (let [payload (byte-array (- (alength data) 4))]
              (System/arraycopy data 4 payload 0 (alength payload))
              (proto/decode-payload payload)))))
      (catch java.net.SocketTimeoutException _
        nil)))
  
  (close! [_]
    (.close socket))
  
  (connected? [_]
    (not (.isClosed socket))))

(defn udp-connect
  "Create UDP transport for matching engine."
  [host port & {:keys [timeout] :or {timeout 1000}}]
  (let [socket (DatagramSocket.)
        addr (InetAddress/getByName host)]
    (.setSoTimeout socket timeout)
    (->UdpTransport socket addr port (byte-array 65536))))

;; =============================================================================
;; Multicast Receiver (read-only, for market data)
;; =============================================================================

(defrecord MulticastTransport [^MulticastSocket socket ^InetAddress group
                               ^bytes recv-buf running?]
  Transport
  (send-msg! [_ _]
    (throw (ex-info "Multicast transport is read-only" {})))
  
  (recv-msg! [_]
    (when @running?
      (try
        (let [pkt (DatagramPacket. recv-buf (alength recv-buf))]
          (.receive socket pkt)
          (let [data (byte-array (.getLength pkt))]
            (System/arraycopy (.getData pkt) 0 data 0 (.getLength pkt))
            (when (>= (alength data) 4)
              (let [payload (byte-array (- (alength data) 4))]
                (System/arraycopy data 4 payload 0 (alength payload))
                (proto/decode-payload payload)))))
        (catch java.net.SocketTimeoutException _
          nil)
        (catch java.io.IOException _
          nil))))
  
  (close! [_]
    (reset! running? false)
    (try
      (.leaveGroup socket group)
      (.close socket)
      (catch Exception _)))
  
  (connected? [_]
    (and @running? (not (.isClosed socket)))))

(defn multicast-join
  "Join a multicast group for market data.
   
   Options:
     :interface - network interface name (e.g., \"eth0\")
     :timeout   - receive timeout in ms"
  [group-addr port & {:keys [interface timeout] :or {timeout 1000}}]
  (let [group (InetAddress/getByName group-addr)
        socket (MulticastSocket. port)]
    (.setSoTimeout socket timeout)
    (if interface
      (let [iface (NetworkInterface/getByName interface)]
        (.joinGroup socket (InetSocketAddress. group port) iface))
      (.joinGroup socket group))
    (->MulticastTransport socket group (byte-array 65536) (atom true))))

;; =============================================================================
;; Connection Factory
;; =============================================================================

(defn connect
  "Create a transport connection.
   
   Examples:
     (connect :tcp \"localhost\" 1234)
     (connect :udp \"localhost\" 1234)
     (connect :multicast \"239.255.1.1\" 5000)
     (connect :multicast \"239.255.1.1\" 5000 :interface \"eth0\")"
  [transport-type host port & opts]
  (case transport-type
    :tcp       (apply tcp-connect host port opts)
    :udp       (apply udp-connect host port opts)
    :multicast (apply multicast-join host port opts)
    (throw (ex-info "Unknown transport type" {:type transport-type}))))

;; =============================================================================
;; Message Loop
;; =============================================================================

(defn message-loop
  "Blocking loop that reads messages and calls handler.
   Returns when transport closes or handler returns :stop.
   
   Options:
     :error-fn - called on exceptions, default prints to stderr"
  [transport handler & {:keys [error-fn]
                        :or {error-fn #(binding [*out* *err*]
                                         (println "Transport error:" %))}}]
  (loop []
    (when (connected? transport)
      (let [result (try
                     (when-let [msg (recv-msg! transport)]
                       (handler msg))
                     (catch Exception e
                       (error-fn e)
                       :continue))]
        (when-not (= result :stop)
          (recur))))))

(defn async-message-loop
  "Start message loop in a background thread. Returns the thread."
  [transport handler & opts]
  (let [t (Thread. #(apply message-loop transport handler opts))]
    (.setDaemon t true)
    (.setName t "transport-reader")
    (.start t)
    t))
