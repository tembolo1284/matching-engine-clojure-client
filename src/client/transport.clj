(ns client.transport
  "Transport layer for TCP, UDP, and multicast connections.
   Used by relay for receiving market data."
  (:require [client.protocol :as proto])
  (:import [java.net Socket DatagramSocket DatagramPacket
            InetAddress InetSocketAddress MulticastSocket NetworkInterface]
           [java.io BufferedInputStream BufferedOutputStream DataInputStream DataOutputStream]))

;; =============================================================================
;; Protocol
;; =============================================================================

(defprotocol Transport
  (send-msg! [this msg])
  (recv-msg! [this])
  (close! [this])
  (connected? [this]))

;; =============================================================================
;; Helper - convert ByteBuffer to bytes
;; =============================================================================

(defn- buf->bytes
  "Convert ByteBuffer to byte array."
  [^java.nio.ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get buf arr)
    arr))

;; =============================================================================
;; TCP Transport (length-prefixed framing)
;; =============================================================================

(defrecord TcpTransport [^Socket socket ^DataInputStream in ^DataOutputStream out]
  Transport
  (send-msg! [_ msg]
    ;; Send using length-prefixed framing (4-byte big-endian length)
    (let [data (case (:type msg)
                 :new-order (buf->bytes (proto/encode-new-order
                                         (:user-id msg)
                                         (:symbol msg)
                                         (:price msg)
                                         (:qty msg)
                                         (:side msg)
                                         (:order-id msg)))
                 :cancel    (buf->bytes (proto/encode-cancel
                                         (:user-id msg)
                                         (:symbol msg)
                                         (:order-id msg)))
                 :flush     (buf->bytes (proto/encode-flush))
                 (throw (ex-info "Unknown message type" {:type (:type msg)})))]
      (.writeInt out (alength data))
      (.write out data)
      (.flush out)))
  
  (recv-msg! [_]
    ;; Read length-prefixed frame
    (let [len (.readInt in)]
      (when (and (pos? len) (< len 65536))
        (let [arr (byte-array len)]
          (.readFully in arr)
          (proto/decode-auto arr)))))
  
  (close! [_]
    (try (.close socket) (catch Exception _)))
  
  (connected? [_]
    (and (not (.isClosed socket))
         (.isConnected socket))))

(defn tcp-connect
  [host port & {:keys [timeout] :or {timeout 5000}}]
  (let [socket (Socket.)]
    (.connect socket (InetSocketAddress. ^String host ^int port) timeout)
    (.setSoTimeout socket 100)
    (.setTcpNoDelay socket true)
    (->TcpTransport
     socket
     (DataInputStream. (BufferedInputStream. (.getInputStream socket)))
     (DataOutputStream. (BufferedOutputStream. (.getOutputStream socket))))))

;; =============================================================================
;; UDP Transport
;; =============================================================================

(defrecord UdpTransport [^DatagramSocket socket ^InetAddress addr ^int port
                         ^bytes recv-buf]
  Transport
  (send-msg! [_ msg]
    (let [data (case (:type msg)
                 :new-order (buf->bytes (proto/encode-new-order
                                         (:user-id msg)
                                         (:symbol msg)
                                         (:price msg)
                                         (:qty msg)
                                         (:side msg)
                                         (:order-id msg)))
                 :cancel    (buf->bytes (proto/encode-cancel
                                         (:user-id msg)
                                         (:symbol msg)
                                         (:order-id msg)))
                 :flush     (buf->bytes (proto/encode-flush))
                 (throw (ex-info "Unknown message type" {:type (:type msg)})))
          pkt (DatagramPacket. data (alength data) addr port)]
      (.send socket pkt)))
  
  (recv-msg! [_]
    (let [pkt (DatagramPacket. recv-buf (alength recv-buf))]
      (.receive socket pkt)
      (let [data (byte-array (.getLength pkt))]
        (System/arraycopy (.getData pkt) 0 data 0 (.getLength pkt))
        (proto/decode-auto data))))
  
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
;; Multicast Receiver (read-only)
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
          (proto/decode-auto data)))))
  
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
