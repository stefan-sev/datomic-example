(ns bad-bot.server
  (:gen-class)
  (:require [bad-bot.service :as service]
            [io.pedestal.http :as http]))

(def service-map
  {::http/routes service/routes
   ::http/type   :jetty
   ::http/port   8890
   ::http/secure-headers []})

(defn start []
  (http/start (http/create-server service-map)))

;; For interactive development
(defonce server (atom nil))

(defn start-dev []
  (reset! server
          (http/start (http/create-server
                       (assoc service-map
                              ::http/join? false)))))

(defn stop-dev []
  (http/stop @server))

(defn restart []
  (stop-dev)
  (start-dev))

