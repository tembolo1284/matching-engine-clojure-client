(ns me-client.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [me-client.relay.config :as config]))

;; =============================================================================
;; CLI Parsing Tests
;; =============================================================================

(deftest parse-cli-test
  (testing "Engine host:port parsing"
    (let [result (config/parse-cli ["-e" "192.168.1.100:9000"])]
      (is (= "192.168.1.100" (:engine-host result)))
      (is (= 9000 (:engine-port result)))))
  
  (testing "Long form engine"
    (let [result (config/parse-cli ["--engine" "host:1234"])]
      (is (= "host" (:engine-host result)))
      (is (= 1234 (:engine-port result)))))
  
  (testing "Transport type"
    (is (= :tcp (:engine-transport (config/parse-cli ["-t" "tcp"]))))
    (is (= :udp (:engine-transport (config/parse-cli ["-t" "udp"]))))
    (is (= :multicast (:engine-transport (config/parse-cli ["-t" "multicast"])))))
  
  (testing "Multicast options"
    (let [result (config/parse-cli ["-m" "239.255.1.1" "-i" "eth0"])]
      (is (= "239.255.1.1" (:multicast-group result)))
      (is (= "eth0" (:multicast-iface result)))))
  
  (testing "WebSocket port"
    (is (= 9000 (:ws-port (config/parse-cli ["-w" "9000"]))))
    (is (= 8080 (:ws-port (config/parse-cli ["--ws-port" "8080"])))))
  
  (testing "Bind address"
    (is (= "127.0.0.1" (:ws-host (config/parse-cli ["-b" "127.0.0.1"])))))
  
  (testing "Message filter"
    (let [result (config/parse-cli ["-f" "trade,order-ack"])]
      (is (= #{:trade :order-ack} (:message-filter result)))))
  
  (testing "No static flag"
    (is (= false (:serve-static (config/parse-cli ["--no-static"])))))
  
  (testing "Verbose flag"
    (is (= :debug (:log-level (config/parse-cli ["-v"])))))
  
  (testing "Help flag"
    (is (true? (:help (config/parse-cli ["-h"])))))
  
  (testing "Multiple options"
    (let [result (config/parse-cli ["-e" "host:9000" "-w" "8080" "-v" "--no-static"])]
      (is (= "host" (:engine-host result)))
      (is (= 9000 (:engine-port result)))
      (is (= 8080 (:ws-port result)))
      (is (= :debug (:log-level result)))
      (is (= false (:serve-static result))))))

;; =============================================================================
;; Config Loading Tests
;; =============================================================================

(deftest load-config-test
  (testing "Defaults applied"
    (let [config (config/load-config [])]
      (is (= "localhost" (:engine-host config)))
      (is (= 1234 (:engine-port config)))
      (is (= :tcp (:engine-transport config)))
      (is (= 8080 (:ws-port config)))
      (is (= "0.0.0.0" (:ws-host config)))
      (is (true? (:serve-static config)))
      (is (= :info (:log-level config)))))
  
  (testing "CLI overrides defaults"
    (let [config (config/load-config ["-e" "remote:9000" "-w" "3000"])]
      (is (= "remote" (:engine-host config)))
      (is (= 9000 (:engine-port config)))
      (is (= 3000 (:ws-port config)))
      ;; Defaults still applied
      (is (= :tcp (:engine-transport config))))))

;; =============================================================================
;; Validation Tests
;; =============================================================================

(deftest validate-test
  (testing "Valid TCP config"
    (let [config (config/load-config [])
          [valid? errors] (config/validate config)]
      (is valid?)
      (is (empty? errors))))
  
  (testing "Multicast requires group"
    (let [config (assoc (config/load-config []) :engine-transport :multicast)
          [valid? errors] (config/validate config)]
      (is (not valid?))
      (is (some #(clojure.string/includes? % "multicast") errors))))
  
  (testing "Multicast with group is valid"
    (let [config (config/load-config ["-t" "multicast" "-m" "239.255.1.1"])
          [valid? errors] (config/validate config)]
      (is valid?)
      (is (empty? errors))))
  
  (testing "Invalid port"
    (let [config (assoc (config/load-config []) :engine-port -1)
          [valid? errors] (config/validate config)]
      (is (not valid?))))
  
  (testing "Empty filter invalid"
    (let [config (assoc (config/load-config []) :message-filter #{})
          [valid? errors] (config/validate config)]
      (is (not valid?)))))
