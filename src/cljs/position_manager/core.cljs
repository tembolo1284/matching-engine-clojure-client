(ns position-manager.core
  "Application entry point."
  (:require [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [position-manager.config :as config]
            [position-manager.events]
            [position-manager.subs]
            [position-manager.websocket]
            [position-manager.views :as views]))

(defn ^:dev/after-load mount-root []
  (rf/clear-subscription-cache!)
  (let [root-el (.getElementById js/document "app")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [views/app] root-el)))

(defn init! []
  (rf/dispatch-sync [:app/initialize])
  (mount-root)
  (when config/DEBUG
    (js/console.log "Position Manager initialized (debug mode)")))
