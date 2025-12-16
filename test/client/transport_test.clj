(ns me-client.transport-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [me-client.transport :as transport]
            [me-client.protocol :as proto])
  (:import [java.net ServerSocket Socket InetSocketAddress]
           [java.io BufferedInputStream BufferedOutputStream]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-port* nil)
(def ^:dynamic *server-socket* nil)

(defn with-test-server [f]
  (let [server (ServerSocket. 0)  ; Random available port
        port (.getLocalPort server)]
    (binding [*test-port* port
              *server-socket* server]
      (try
        (f)
        (finally
          (.close server))))))

(use-fixtures :each with-test-server)

;; =============================================================================
;; TCP Transport Tests
;; =============================================================================

(deftest tcp-connect-test
  (testing "Successful TCP connection"
    ;; Accept connection in background
    (let [accepted (promise)]
      (future
        (deliver accepted (.accept *server-socket*)))
      
      (let [tp (transport/tcp-connect "localhost" *test-port* :timeout 1000)]
        (try
          (is (transport/connected? tp))
          (is (some? @accepted))
          (finally
            (transport/close! tp)
            (.close @accepted)))))))

(deftest tcp-connect-timeout-test
  (testing "Connection timeout"
    ;; Don't accept - should timeout
    (is (thrown? java.net.SocketTimeoutException
                 (transport/tcp-connect "localhost" *test-port* :timeout 100)))))

(deftest tcp-send-recv-test
  (testing "Send and receive messages"
    (let [server-client (promise)
          received (promise)]
      ;; Server side
      (future
        (let [client (.accept *server-socket*)
              in (BufferedInputStream. (.getInputStream client))
              out (BufferedOutputStream. (.getOutputStream client))]
          (deliver server-client {:socket client :in in :out out})
          ;; Read frame, echo back an ack
          (let [msg (proto/read-frame in)
                ack (byte-array [0x4D 0x45  ; magic
                                 0x11 0x00  ; length = 17
                                 0x10       ; type = order-ack
                                 0x01 0x00 0x00 0x00  ; user-id
                                 0x01 0x00 0x00 0x00  ; order-id
                                 0x49 0x42 0x4D 0x20 0x20 0x20 0x20 0x20])]  ; symbol
            (deliver received msg)
            (.write out ack)
            (.flush out))))
      
      ;; Client side
      (let [tp (transport/tcp-connect "localhost" *test-port*)]
        (try
          (transport/send-msg! tp {:type :new-order
                                   :user-id 1
                                   :order-id 1
                                   :side :buy
                                   :symbol "IBM"
                                   :price 100.0
                                   :qty 100})
          
          ;; Wait for server to process
          (Thread/sleep 100)
          
          ;; Verify server received message
          (is (= :new-order (:type @received)))
          
          ;; Read response
          (let [response (transport/recv-msg! tp)]
            (is (= :order-ack (:type response)))
            (is (= 1 (:user-id response))))
          
          (finally
            (transport/close! tp)
            (when-let [{:keys [socket]} @server-client]
              (.close socket))))))))

(deftest tcp-close-test
  (testing "Close connection"
    (let [accepted (promise)]
      (future (deliver accepted (.accept *server-socket*)))
      
      (let [tp (transport/tcp-connect "localhost" *test-port*)]
        (is (transport/connected? tp))
        (transport/close! tp)
        (is (not (transport/connected? tp)))
        (.close @accepted)))))

;; =============================================================================
;; UDP Transport Tests
;; =============================================================================

(deftest udp-connect-test
  (testing "UDP transport creation"
    (let [tp (transport/udp-connect "localhost" 9999)]
      (try
        (is (transport/connected? tp))
        (finally
          (transport/close! tp))))))

(deftest udp-close-test
  (testing "UDP close"
    (let [tp (transport/udp-connect "localhost" 9999)]
      (transport/close! tp)
      (is (not (transport/connected? tp))))))

;; =============================================================================
;; Connect Factory Tests
;; =============================================================================

(deftest connect-factory-test
  (testing "TCP via factory"
    (let [accepted (promise)]
      (future (deliver accepted (.accept *server-socket*)))
      
      (let [tp (transport/connect :tcp "localhost" *test-port*)]
        (try
          (is (transport/connected? tp))
          (finally
            (transport/close! tp)
            (.close @accepted))))))
  
  (testing "UDP via factory"
    (let [tp (transport/connect :udp "localhost" 9999)]
      (try
        (is (transport/connected? tp))
        (finally
          (transport/close! tp)))))
  
  (testing "Unknown transport throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (transport/connect :invalid "localhost" 1234)))))

;; =============================================================================
;; Message Loop Tests
;; =============================================================================

(deftest async-message-loop-test
  (testing "Async loop processes messages"
    (let [server-client (promise)
          messages (atom [])]
      ;; Server sends multiple messages
      (future
        (let [client (.accept *server-socket*)
              out (BufferedOutputStream. (.getOutputStream client))]
          (deliver server-client client)
          (Thread/sleep 50)
          ;; Send 3 acks
          (dotimes [i 3]
            (let [ack (byte-array [0x4D 0x45 0x11 0x00 0x10
                                   (unchecked-byte (inc i)) 0x00 0x00 0x00
                                   (unchecked-byte (inc i)) 0x00 0x00 0x00
                                   0x49 0x42 0x4D 0x20 0x20 0x20 0x20 0x20])]
              (.write out ack)
              (.flush out)
              (Thread/sleep 20)))))
      
      (let [tp (transport/tcp-connect "localhost" *test-port*)
            thread (transport/async-message-loop tp #(swap! messages conj %))]
        (try
          (Thread/sleep 200)
          (is (= 3 (count @messages)))
          (is (every? #(= :order-ack (:type %)) @messages))
          (finally
            (transport/close! tp)
            (.close @server-client)))))))
