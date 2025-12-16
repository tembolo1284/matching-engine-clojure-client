(ns me-client.engine-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [me-client.relay.engine :as engine]
            [me-client.protocol :as proto])
  (:import [java.net ServerSocket]
           [java.io BufferedOutputStream]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-port* nil)
(def ^:dynamic *server-socket* nil)

(defn with-test-server [f]
  (let [server (ServerSocket. 0)
        port (.getLocalPort server)]
    (binding [*test-port* port
              *server-socket* server]
      (try
        (f)
        (finally
          (engine/stop-relay!)
          (.close server))))))

(use-fixtures :each with-test-server)

;; =============================================================================
;; Connection Tests
;; =============================================================================

(deftest connect-test
  (testing "Successful connection"
    (let [accepted (promise)]
      (future
        (deliver accepted (.accept *server-socket*)))
      
      (let [result (engine/connect! {:host "localhost"
                                     :port *test-port*
                                     :transport-type :tcp})]
        (is (= :tcp (:transport result)))
        (is (= "localhost" (:host result)))
        (is (= *test-port* (:port result)))
        (is (engine/connected?))
        
        (engine/disconnect!)
        (.close @accepted)))))

(deftest disconnect-test
  (testing "Disconnect cleans up"
    (let [accepted (promise)]
      (future
        (deliver accepted (.accept *server-socket*)))
      
      (engine/connect! {:host "localhost"
                        :port *test-port*
                        :transport-type :tcp})
      
      (is (engine/connected?))
      (engine/disconnect!)
      (is (not (engine/connected?)))
      
      (.close @accepted))))

(deftest double-connect-throws-test
  (testing "Double connect throws"
    (let [accepted (atom [])]
      (future
        (swap! accepted conj (.accept *server-socket*)))
      
      (engine/connect! {:host "localhost"
                        :port *test-port*
                        :transport-type :tcp})
      
      (is (thrown? clojure.lang.ExceptionInfo
                   (engine/connect! {:host "localhost"
                                     :port *test-port*
                                     :transport-type :tcp})))
      
      (engine/disconnect!)
      (doseq [s @accepted] (.close s)))))

;; =============================================================================
;; Handler Tests
;; =============================================================================

(deftest handler-test
  (testing "Handler receives filtered messages"
    (let [accepted (promise)
          received (atom [])]
      
      ;; Server sends messages
      (future
        (let [client (.accept *server-socket*)
              out (BufferedOutputStream. (.getOutputStream client))]
          (deliver accepted client)
          (Thread/sleep 100)
          ;; Send a trade
          (let [msg (byte-array [0x4D 0x45  ; magic
                                 0x25 0x00  ; length = 37
                                 0x20       ; type = trade
                                 0x49 0x42 0x4D 0x20 0x20 0x20 0x20 0x20  ; symbol
                                 0x00 0x00 0x00 0x00 0x00 0x20 0x59 0x40  ; price
                                 0x64 0x00 0x00 0x00  ; qty
                                 0x01 0x00 0x00 0x00  ; buy-user
                                 0x01 0x00 0x00 0x00  ; buy-order
                                 0x02 0x00 0x00 0x00  ; sell-user
                                 0x02 0x00 0x00 0x00])]  ; sell-order
            (.write out msg)
            (.flush out))))
      
      ;; Connect and add handler
      (engine/connect! {:host "localhost"
                        :port *test-port*
                        :transport-type :tcp
                        :filter #{:trade}})
      
      (engine/add-handler! #(swap! received conj %))
      
      (Thread/sleep 300)
      
      (is (= 1 (count @received)))
      (is (= :trade (:type (first @received))))
      
      (engine/disconnect!)
      (.close @accepted))))

(deftest filter-test
  (testing "Non-matching messages are filtered"
    (let [accepted (promise)
          received (atom [])]
      
      (future
        (let [client (.accept *server-socket*)
              out (BufferedOutputStream. (.getOutputStream client))]
          (deliver accepted client)
          (Thread/sleep 100)
          ;; Send an order-ack (not in filter)
          (let [msg (byte-array [0x4D 0x45 0x11 0x00 0x10
                                 0x01 0x00 0x00 0x00
                                 0x01 0x00 0x00 0x00
                                 0x49 0x42 0x4D 0x20 0x20 0x20 0x20 0x20])]
            (.write out msg)
            (.flush out))))
      
      (engine/connect! {:host "localhost"
                        :port *test-port*
                        :transport-type :tcp
                        :filter #{:trade}})  ; Only trades
      
      (engine/add-handler! #(swap! received conj %))
      
      (Thread/sleep 300)
      
      ;; Message should be filtered out
      (is (empty? @received))
      
      (let [stats (engine/stats)]
        (is (= 1 (:received stats)))
        (is (= 1 (:filtered stats)))
        (is (= 0 (:dispatched stats))))
      
      (engine/disconnect!)
      (.close @accepted))))

(deftest set-filter-test
  (testing "Filter can be changed"
    (engine/set-filter! #{:trade :order-ack})
    (is (= #{:trade :order-ack} (engine/get-filter)))
    
    (engine/set-filter! #{:trade})
    (is (= #{:trade} (engine/get-filter)))))

;; =============================================================================
;; Stats Tests
;; =============================================================================

(deftest stats-test
  (testing "Stats tracking"
    (let [accepted (promise)]
      (future
        (deliver accepted (.accept *server-socket*)))
      
      (engine/connect! {:host "localhost"
                        :port *test-port*
                        :transport-type :tcp})
      
      (let [stats (engine/stats)]
        (is (= 0 (:received stats)))
        (is (= 0 (:filtered stats)))
        (is (= 0 (:dispatched stats)))
        (is (= 0 (:errors stats))))
      
      (engine/reset-stats!)
      
      (let [stats (engine/stats)]
        (is (= 0 (:received stats))))
      
      (engine/disconnect!)
      (.close @accepted))))

;; =============================================================================
;; Connection Info Tests
;; =============================================================================

(deftest connection-info-test
  (testing "Connection info when connected"
    (let [accepted (promise)]
      (future
        (deliver accepted (.accept *server-socket*)))
      
      (engine/connect! {:host "localhost"
                        :port *test-port*
                        :transport-type :tcp
                        :filter #{:trade}})
      
      (let [info (engine/connection-info)]
        (is (:running? info))
        (is (some? (:stats info)))
        (is (= #{:trade} (:filter info))))
      
      (engine/disconnect!)
      (.close @accepted)))
  
  (testing "Connection info when disconnected"
    (is (nil? (engine/connection-info)))))

;; =============================================================================
;; Start/Stop Relay Tests
;; =============================================================================

(deftest start-relay-test
  (testing "Start relay convenience function"
    (let [accepted (promise)
          received (atom [])]
      (future
        (deliver accepted (.accept *server-socket*)))
      
      (engine/start-relay!
       {:host "localhost"
        :port *test-port*
        :transport-type :tcp}
       #(swap! received conj %))
      
      (is (engine/connected?))
      (is (= 1 (count (:handlers @#'engine/state))))
      
      (engine/stop-relay!)
      
      (is (not (engine/connected?)))
      (is (empty? (:handlers @#'engine/state)))
      
      (.close @accepted))))
