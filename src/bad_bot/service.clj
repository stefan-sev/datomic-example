(ns bad-bot.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition.table :refer [table-routes]]
            [io.pedestal.http.body-params :as body-params]
            [clojure.data.json :as json]
            [ring.util.response :as ring-resp]
            [com.rpl.specter :as s]
            [clojure.core.async :as async :exclude [map]]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.local :as lt]
            [datomic.client.api :as d]
            [datomic.client.api.async :as async-d]
            [clojure.instant :as inst]))

(import java.util.Date)
(refer-clojure :exclude [range iterate format max min])
(use 'java-time)
(defn now-string [] (str (local-date-time)))
(defn now-inst []
  (inst/read-instant-date (now-string)))

;; datomic config
(def cfg {:server-type :peer-server
          :access-key "admin"
          :secret "pw"
          :endpoint "localhost:8998"})

(def client (d/client cfg))
(def conn (d/connect client {:db-name "bank"}))
(def db (d/db conn))

;; datomic scheme
(def transaction-schema [{:db/ident :transaction/amount
                          :db/valueType :db.type/bigdec
                          :db/cardinality :db.cardinality/one
                          :db/doc "amount of transaction"}

                         {:db/ident :transaction/timestamp
                          :db/valueType :db.type/instant
                          :db/cardinality :db.cardinality/one
                          :db/doc "time of transaction"}

                         {:db/ident :t-statistics/sum
                          :db/valueType :db.type/bigdec
                          :db/cardinality :db.cardinality/one
                          :db/unique :db.unique/identity
                          :db/doc "sum of last sixty seconds"}

                         {:t-statistics/sum (bigdec 0)}])

(d/transact conn {:tx-data transaction-schema})
(def statistic-id
  (let [[[id]] (d/q '[:find ?eid 
                      :where [?eid :t-statistics/sum]] (d/db conn)) ]
  id))

(defn sum [collection]
  (reduce + collection))

(defn calc-statistic [transactions]
  (let [amounts (s/select [s/ALL s/ALL :transaction/amount] transactions)
        s (sum amounts)]
    {:sum   s }))

;;(def stati (map (fn [[eid v]] [:db/retract eid :transaction-statistics/sum v]) 
;;     (d/q '[:find ?e ?v
;;            :where [?e :statistics/sum ?v]]
;;            db)))

;;(d/transact conn {:tx-data stati})

;; queries
(def all-transaction-q '[:find (pull ?e [:transaction/amount :transaction/timestamp]) 
                         :in $ ?latest
                         :where [?e :transaction/amount]
                         [?e :transaction/timestamp ?date]
                         [(<= ?latest ?date)]
                         ])

(def statistic-q '[:find (pull ?eid [:t-statistics/sum])
                    :where [?eid :t-statistics/sum]])

(defn get-statistics [db]
  (->
   (d/pull db '[:t-statistics/sum] statistic-id)
   :t-statistics/sum))
(get-statistics (d/db conn))


(defn seconds-in-the-past [t]
  (-> (lt/local-now)
      (t/minus (t/seconds t))
      (str)
      (inst/read-instant-date)))

(defn convert-to-transaction-map [vector]
  (let [[amount time] vector]
    {:amount amount :timestamp time }))

(defn get-transactions-from
  ([seconds]
   (get-transactions-from seconds (d/db conn)))
  ([seconds db]
   (let [since (seconds-in-the-past seconds)
         transactions (d/q all-transaction-q db since)]
     transactions)))

;; transactors
(defn uuid []
  (-> (java.util.UUID/randomUUID) (.toString)))

(defn add-transaction-db [{:keys [amount timestamp] :or {amount 0, timestamp (java.util.Date.)}}]
  (let [transaction {:db/id (uuid) 
                                :transaction/amount (bigdec amount)
                                :transaction/timestamp (if (inst? timestamp)
                                                         timestamp
                                                         (inst/read-instant-date timestamp))}
        db (d/with-db conn)
        udb (-> db (d/with {:tx-data [transaction]} ) :db-after)
        currentSum (get-statistics udb)
        transactions (get-transactions-from 60 udb)
        newSum (:sum (calc-statistic transactions))]
    (d/transact conn {:tx-data [transaction
                                [:db/cas statistic-id :t-statistics/sum currentSum newSum]]})
    ))

(defn str->time [s]
  (lt/to-local-date-time s))

(defn cleanup [state]
  (as-> state s
    (s/setval [:transactions s/ALL #(t/before? (:timestamp %) (seconds-in-the-past 10))]
              s/NONE s)
    (assoc s :statistics (calc-statistic (:transactions s)))))

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers (conj {"Content-Type" "application/json"} headers)})

(def ok (partial response 200))
(def noContent (partial response 204 ""))

(defn answerResponse [text]
  {:messageText text})

(def transactions {:name :transactions
                   :enter
                   (fn [context]
                     (let [answerJson ()
                           resp (ok answerJson)]
                       (assoc context :response resp)))})

(def create-transaction {:name :createTransaction
                         :enter
                         (fn [context]
                           (let [body (slurp (:body (:request context)))
                                 tx (json/read-str body :key-fn keyword)]
                             (add-transaction-db tx)
                             (assoc context :response (noContent))))})

(def statistic {:name :statistics
                :enter
                (fn [context]
                  (let [statistics ()
                        stats (json/write-str statistics)
                        resp (ok stats)]
                    (assoc context :response resp)))})


;; Tabular routes


(def routes
  (route/expand-routes
   #{["/transactions" :get transactions :route-name :transactions]
     ["/transaction" :post create-transaction :route-name :createTransaction]
     ["/statistics" :get statistic :route-name :statistics]}))

(defn print-routes
  "Print our application's routes"
  []
  (route/print-routes routes))



;; Consumed by bad-bot.server/create-server
;; See http/default-interceptors for additional options you can configure


(def service {:env                     :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
              ::http/routes            routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::http/allowed-origins ["scheme://host:port"]

              ;; Tune the Secure Headers
              ;; and specifically the Content Security Policy appropriate to your service/application
              ;; For more information, see: https://content-security-policy.com/
              ;;   See also: https://github.com/pedestal/pedestal/issues/499
              ;;::http/secure-headers {:content-security-policy-settings {:object-src "'none'"
              ;;                                                          :script-src "'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:"
              ;;                                                          :frame-ancestors "'none'"}}

              ;; Root for resource interceptor that is available by default.
              ::http/resource-path     "/public"

              ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
              ;;  This can also be your own chain provider/server-fn -- http://pedestal.io/reference/architecture-overview#_chain_provider
              ::http/type              :jetty
              ;;::http/host "localhost"
              ::http/port              8080
              ;; Options to pass to the container (Jetty)
              ::http/container-options {:h2c? true
                                        :h2?  false
                                        ;:keystore "test/hp/keystore.jks"
                                        ;:key-password "password"
                                        ;:ssl-port 8443
                                        :ssl? false}})

