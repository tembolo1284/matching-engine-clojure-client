(ns position-manager.events
  "Re-frame event handlers."
  (:require [re-frame.core :as rf]
            [position-manager.db :as db]
            [position-manager.config :as config]))

;; =============================================================================
;; Initialization
;; =============================================================================

(rf/reg-event-fx
 :app/initialize
 (fn [_ _]
   {:db db/default-db
    :dispatch [:ws/connect]}))

;; =============================================================================
;; WebSocket Connection Events
;; =============================================================================

(rf/reg-event-fx
 :ws/connect
 (fn [{:keys [db]} [_ url]]
   (let [ws-url (or url config/default-ws-url)]
     {:db (assoc-in db [:connection :status] :connecting)
      :ws/connect ws-url})))

(rf/reg-event-fx
 :ws/disconnect
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:connection :status] :disconnected)
            (assoc-in [:connection :reconnect-attempts] 0))
    :ws/disconnect nil}))

(rf/reg-event-fx
 :ws/connected
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:connection :status] :connected)
            (assoc-in [:connection :reconnect-attempts] 0)
            (assoc-in [:stats :connected-at] (js/Date.)))
    :dispatch [:log/add {:level :info
                         :message "Connected to relay server"}]}))

(rf/reg-event-fx
 :ws/disconnected
 (fn [{:keys [db]} _]
   (let [attempts (inc (get-in db [:connection :reconnect-attempts]))]
     {:db (-> db
              (assoc-in [:connection :status] :disconnected)
              (assoc-in [:connection :reconnect-attempts] attempts))
      :dispatch [:log/add {:level :warn
                           :message (str "Disconnected (attempt " attempts ")")}]
      :dispatch-later [{:ms config/reconnect-delay-ms
                        :dispatch [:ws/connect]}]})))

(rf/reg-event-fx
 :ws/error
 (fn [{:keys [db]} [_ error-msg]]
   {:dispatch [:log/add {:level :error
                         :message (str "WebSocket error: " error-msg)}]}))

(rf/reg-event-fx
 :ws/connecting
 (fn [{:keys [db]} [_ url]]
   {:db (-> db
            (assoc-in [:connection :status] :connecting)
            (assoc-in [:connection :url] url))}))

;; =============================================================================
;; Message Handling
;; =============================================================================

(rf/reg-event-fx
 :ws/message
 (fn [{:keys [db]} [_ raw-data]]
   (try
     (let [msg (js->clj (js/JSON.parse raw-data) :keywordize-keys true)]
       {:db (update-in db [:stats :messages-received] inc)
        :dispatch [:message/process msg]})
     (catch js/Error e
       {:dispatch [:log/add {:level :error
                             :message (str "Parse error: " (.-message e))}]}))))

(rf/reg-event-fx
 :message/process
 (fn [_ [_ msg]]
   (let [msg-type (keyword (:type msg))]
     {:dispatch (case msg-type
                  :order-ack     [:order/ack msg]
                  :order-reject  [:order/reject msg]
                  :cancel-ack    [:order/cancel-ack msg]
                  :cancel-reject [:order/cancel-reject msg]
                  :trade         [:trade/executed msg]
                  :book-update   [:book/update msg]
                  [:log/add {:level :warn
                             :message (str "Unknown message type: " msg-type)}])})))

;; =============================================================================
;; Order Events
;; =============================================================================

(defn- order-key [{:keys [user-id order-id]}]
  (str user-id "-" order-id))

(rf/reg-event-fx
 :order/ack
 (fn [{:keys [db]} [_ msg]]
   (let [key (order-key msg)
         order {:key key
                :user-id (:user-id msg)
                :order-id (:order-id msg)
                :symbol (:symbol msg)
                :status :active
                :acked-at (js/Date.)}]
     {:db (-> db
              (assoc-in [:orders key] order)
              (update-in [:stats :orders-acked] inc))
      :dispatch [:log/add {:level :success
                           :message (str "Order #" (:order-id msg) " " (:symbol msg) " acknowledged")}]})))

(rf/reg-event-fx
 :order/reject
 (fn [{:keys [db]} [_ msg]]
   {:db (update-in db [:stats :orders-rejected] inc)
    :dispatch [:log/add {:level :error
                         :message (str "Order #" (:order-id msg) " rejected (reason: " (:reason msg) ")")}]}))

(rf/reg-event-fx
 :order/cancel-ack
 (fn [{:keys [db]} [_ msg]]
   (let [key (order-key msg)]
     {:db (update db :orders dissoc key)
      :dispatch [:log/add {:level :info
                           :message (str "Cancel #" (:order-id msg) " " (:symbol msg) " confirmed")}]})))

(rf/reg-event-fx
 :order/cancel-reject
 (fn [{:keys [db]} [_ msg]]
   {:dispatch [:log/add {:level :error
                         :message (str "Cancel #" (:order-id msg) " rejected (reason: " (:reason msg) ")")}]}))

;; =============================================================================
;; Trade Events
;; =============================================================================

(rf/reg-event-fx
 :trade/executed
 (fn [{:keys [db]} [_ msg]]
   (let [buy-key (str (:buy-user-id msg) "-" (:buy-order-id msg))
         sell-key (str (:sell-user-id msg) "-" (:sell-order-id msg))
         trade {:symbol (:symbol msg)
                :price (:price msg)
                :qty (:qty msg)
                :buy-user-id (:buy-user-id msg)
                :buy-order-id (:buy-order-id msg)
                :sell-user-id (:sell-user-id msg)
                :sell-order-id (:sell-order-id msg)
                :executed-at (js/Date.)}
         new-trades (take config/max-trades (cons trade (:trades db)))]
     {:db (-> db
              (update :orders dissoc buy-key sell-key)
              (assoc :trades new-trades)
              (update-in [:stats :trades-count] inc))
      :dispatch [:log/add {:level :trade
                           :message (str "Trade " (:symbol msg) " " (:qty msg) " @ " (.toFixed (:price msg) 2))}]})))

;; =============================================================================
;; Book Events
;; =============================================================================

(rf/reg-event-db
 :book/update
 (fn [db [_ msg]]
   ;; Could track order book state here if needed
   db))

;; =============================================================================
;; Log Events
;; =============================================================================

(rf/reg-event-db
 :log/add
 (fn [db [_ entry]]
   (let [log-entry (assoc entry :timestamp (js/Date.))
         new-log (take config/max-log-entries (cons log-entry (:log db)))]
     (assoc db :log new-log))))

(rf/reg-event-db
 :log/clear
 (fn [db _]
   (assoc db :log [])))

;; =============================================================================
;; UI Events
;; =============================================================================

(rf/reg-event-db
 :ui/select-tab
 (fn [db [_ tab]]
   (assoc-in db [:ui :selected-tab] tab)))

(rf/reg-event-db
 :ui/set-filter
 (fn [db [_ symbol]]
   (assoc-in db [:ui :filter-symbol] symbol)))

(rf/reg-event-db
 :ui/toggle-theme
 (fn [db _]
   (update-in db [:ui :theme] #(if (= % :dark) :light :dark))))
