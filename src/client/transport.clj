(ns client.transport
  "Transport layer for TCP, UDP, and multicast connections."
  (:require [client.protocol :as proto])
  (:import [java.net Socket DatagramSocket DatagramPacket
            InetAddress InetSocketAddress MulticastSocket NetworkInterface]
           [java.io BufferedInputStream BufferedOutputStream]))

;; =============================================================================
;; Protocol
;; =============================================================================

(defprotocol Transport
  (send-msg! [this msg])
  (recv-msg! [this])
  (close! [this])
  (connected? [this]))

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
    ;; Let SocketTimeoutException propagate - caller handles it
    (proto/read-frame in))
  
  (close! [_]
    (try (.close socket) (catch Exception _)))
  
  (connected? [_]
    (and (not (.isClosed socket))
         (.isConnected socket))))

(defn tcp-connect
  [host port & {:keys [timeout] :or {timeout 5000}}]
  (let [socket (Socket.)]
    (.connect socket (InetSocketAddress. ^String host ^int port) timeout)
    ;; Short read timeout so reader thread stays responsive
    (.setSoTimeout socket 100)
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
    (let [pkt (DatagramPacket. recv-buf (alength recv-buf))]
      (.receive socket pkt)
      (let [data (byte-array (.getLength pkt))]
        (System/arraycopy (.getData pkt) 0 data 0 (.getLength pkt))
        (when (>= (alength data) 4)
          (let [payload (byte-array (- (alength data) 4))]
            (System/arraycopy data 4 payload 0 (alength payload))
            (proto/decode-payload payload))))))
  
  (close! [_]
    (.close socket))
  
  (connected? [_]
    (not (.isClosed socket))))

(defn udp-connect
  [host port & {:keys [timeout] :or {timeout 1000}}]
  (let [socket (DatagramSocket.)
        addr (InetAddress/getByName host)]
    (.setSoTimeout socket timeout)
    (->UdpTransport socket addr port (byte-array 65536))))

;; =============================================================================
;; Multicast Receiver
;; =============================================================================

(defrecord MulticastTransport [^MulticastSocket socket ^InetAddress group
                               ^bytes recv-buf running?]
  Transport
  (send-msg! [_ _]
    (throw (ex-info "Multicast transport is read-only" {})))
  
  (recv-msg! [_]
    (when @running?
      (let [pkt (DatagramPacket. recv-buf (alength recv-buf))]
        (.receive socket pkt)
        (let [data (byte-array (.getLength pkt))]
          (System/arraycopy (.getData pkt) 0 data 0 (.getLength pkt))
          (when (>= (alength data) 4)
            (let [payload (byte-array (- (alength data) 4))]
              (System/arraycopy data 4 payload 0 (alength payload))
              (proto/decode-payload payload)))))))
  
  (close! [_]
    (reset! running? false)
    (try
      (.leaveGroup socket group)
      (.close socket)
      (catch Exception _)))
  
  (connected? [_]
    (and @running? (not (.isClosed socket)))))

(defn multicast-join
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
  [transport-type host port & opts]
  (case transport-type
    :tcp       (apply tcp-connect host port opts)
    :udp       (apply udp-connect host port opts)
    :multicast (apply multicast-join host port opts)
    (throw (ex-info "Unknown transport type" {:type transport-type}))))
