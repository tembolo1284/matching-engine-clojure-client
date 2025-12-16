(ns client.websocket-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [client.relay.websocket :as ws])
  (:import [java.net URI]
           [org.java_websocket.client WebSocketClient]
           [org.java_websocket.handshake ServerHandshake]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *ws-port* nil)

(defn with-ws-server [f]
  ;; Find available port
  (let [port (+ 10000 (rand-int 50000))]
    (binding [*ws-port* port]
      (ws/start! {:port port :serve-static? false})
      (try
        (Thread/sleep 100)  ; Let server start
        (f)
        (finally
          (ws/stop!))))))

(use-fixtures :each with-ws-server)

;; =============================================================================
;; Helper: Simple WebSocket Client
;; =============================================================================

(defn create-test-client [port on-message]
  (let [messages (atom [])
        connected (promise)
        client (proxy [WebSocketClient] [(URI. (str "ws://localhost:" port "/ws"))]
                 (onOpen [^ServerHandshake handshake]
                   (deliver connected true))
                 (onMessage [^String message]
                   (swap! messages conj message)
                   (when on-message (on-message message)))
                 (onClose [code reason remote]
                   nil)
                 (onError [^Exception ex]
                   nil))]
    {:client client
     :messages messages
     :connected connected}))

;; =============================================================================
;; Server Lifecycle Tests
;; =============================================================================

(deftest server-lifecycle-test
  (testing "Server starts and stops"
    (is (ws/running?))
    (let [info (ws/server-info)]
      (is (= *ws-port* (:port info)))
      (is (some? (:started-at info))))))

(deftest client-count-test
  (testing "Initial client count is zero"
    (is (= 0 (ws/client-count)))))

;; =============================================================================
;; Broadcasting Tests
;; =============================================================================

(deftest broadcast-test
  (testing "Broadcast to connected clients"
    (let [{:keys [client messages connected]} (create-test-client *ws-port* nil)]
      (try
        (.connect client)
        (is (deref connected 2000 false))
        (Thread/sleep 100)
        
        (is (= 1 (ws/client-count)))
        
        (ws/broadcast! {:type :trade :symbol "IBM" :price 100.0 :qty 50})
        (Thread/sleep 100)
        
        (is (= 1 (count @messages)))
        (is (clojure.string/includes? (first @messages) "trade"))
        (is (clojure.string/includes? (first @messages) "IBM"))
        
        (finally
          (.close client))))))

(deftest broadcast-multiple-clients-test
  (testing "Broadcast reaches all clients"
    (let [c1 (create-test-client *ws-port* nil)
          c2 (create-test-client *ws-port* nil)]
      (try
        (.connect (:client c1))
        (.connect (:client c2))
        (is (deref (:connected c1) 2000 false))
        (is (deref (:connected c2) 2000 false))
        (Thread/sleep 100)
        
        (is (= 2 (ws/client-count)))
        
        (ws/broadcast! {:type :order-ack :user-id 1 :order-id 1 :symbol "AAPL"})
        (Thread/sleep 100)
        
        (is (= 1 (count @(:messages c1))))
        (is (= 1 (count @(:messages c2))))
        
        (finally
          (.close (:client c1))
          (.close (:client c2)))))))

;; =============================================================================
;; Client Events Tests
;; =============================================================================

(deftest client-connect-disconnect-test
  (testing "Client connect and disconnect events"
    (let [events (atom [])
          _ (ws/stop!)
          _ (ws/start! {:port *ws-port*
                        :serve-static? false
                        :on-connect #(swap! events conj [:connect %])
                        :on-disconnect #(swap! events conj [:disconnect %1 %2])})
          _ (Thread/sleep 100)
          {:keys [client connected]} (create-test-client *ws-port* nil)]
      (try
        (.connect client)
        (is (deref connected 2000 false))
        (Thread/sleep 100)
        
        (is (= 1 (count (filter #(= :connect (first %)) @events))))
        
        (.close client)
        (Thread/sleep 100)
        
        (is (= 1 (count (filter #(= :disconnect (first %)) @events))))
        
        (finally
          (try (.close client) (catch Exception _)))))))

;; =============================================================================
;; HTTP Endpoints Tests
;; =============================================================================

(deftest health-endpoint-test
  (testing "Health endpoint returns status"
    (let [url (str "http://localhost:" *ws-port* "/health")
          response (slurp url)]
      (is (clojure.string/includes? response "ok"))
      (is (clojure.string/includes? response "clients")))))

(deftest clients-endpoint-test
  (testing "Clients endpoint returns list"
    (let [url (str "http://localhost:" *ws-port* "/clients")
          response (slurp url)]
      (is (= "[]" response)))))

;; =============================================================================
;; JSON Encoding Tests
;; =============================================================================

(deftest json-encoding-test
  (testing "Message types encoded correctly"
    (let [{:keys [client messages connected]} (create-test-client *ws-port* nil)]
      (try
        (.connect client)
        (is (deref connected 2000 false))
        (Thread/sleep 100)
        
        ;; Various message types
        (ws/broadcast! {:type :trade
                        :symbol "GOOG"
                        :price 2500.50
                        :qty 10
                        :buy-user-id 1
                        :buy-order-id 1
                        :sell-user-id 2
                        :sell-order-id 2})
        (Thread/sleep 50)
        
        (let [msg (first @messages)]
          (is (clojure.string/includes? msg "\"type\":\"trade\""))
          (is (clojure.string/includes? msg "\"symbol\":\"GOOG\""))
          (is (clojure.string/includes? msg "\"price\":2500.5"))
          (is (clojure.string/includes? msg "\"qty\":10")))
        
        (finally
          (.close client))))))
