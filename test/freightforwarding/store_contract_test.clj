(ns freightforwarding.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [freightforwarding.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Acme Trading Co" (:shipper-name (store/shipment s "ship-1"))))
      (is (true? (:registered? (store/shipment s "ship-1"))))
      (is (true? (:verified? (store/shipment s "ship-1"))))
      (is (true? (:registered? (store/shipment s "ship-2"))) "ship-2 registered")
      (is (false? (:verified? (store/shipment s "ship-2"))) "ship-2 unverified")
      (is (false? (:registered? (store/shipment s "ship-3"))) "ship-3 unregistered")
      (is (true? (:registered? (store/carrier s "car-1"))))
      (is (false? (:registered? (store/carrier s "car-2"))) "car-2 unregistered")
      (testing "ship-4 / car-3 are registered+verified but in a jurisdiction with
                no spec-basis -- they isolate the governor's check 5"
        (is (true? (:verified? (store/shipment s "ship-4"))))
        (is (= "ATL" (:jurisdiction (store/shipment s "ship-4"))))
        (is (= "ATL" (:jurisdiction (store/carrier s "car-3")))))
      (is (= ["ship-1" "ship-2" "ship-3" "ship-4" "ship-5"]
             (mapv :id (store/all-shipments s))))
      (is (= ["car-1" "car-2" "car-3"] (mapv :id (store/all-carriers s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/shipment-log s)))
      (is (= [] (store/schedule-log s)))
      (is (= [] (store/carrier-log s)))
      (is (= [] (store/concern-log s)))
      (is (zero? (store/next-sequence s "JPN" :shipment)))
      (is (zero? (store/next-sequence s "JPN" :schedule))))))

(deftest soft-gate-fields-round-trip-on-both-backends
  (testing "both SOFT governor gates read a STRUCTURED field off the shipment
            record. If a backend drops it, the gate silently stops firing there
            -- which is exactly what happened to :shipment/handoff before it was
            added to the Datomic field spec. Assert both survive the round trip."
    (doseq [[label base] [["MemStore" (store/seed-db)]
                          ["DatomicStore" (store/datomic-seed-db)]]]
      (testing label
        (testing ":compliance-checklist survives (seeded on ship-5)"
          (is (= #{:forwarder-registration}
                 (set (:compliance-checklist (store/shipment base "ship-5"))))))
        (testing "absent on a record that never had one"
          (is (nil? (:compliance-checklist (store/shipment base "ship-1")))))
        (let [handoff {:handoff/id "h-1"
                       :handoff/source-actor "cloud-itonami-isic-5210"
                       :handoff/batch-id "b-1"
                       :handoff/product-type-id "p-1"
                       :handoff/quantity-kg 12.5
                       :handoff/dispatched-at-iso "2026-08-06T00:00:00Z"}
              s (store/with-shipments
                  base
                  {"ship-rt" {:id "ship-rt" :shipper-name "Round Trip Co"
                              :destination "NLRTM" :jurisdiction "JPN"
                              :registered? true :verified? true
                              :compliance-checklist #{:forwarder-registration
                                                      :customs-broker-licence
                                                      :financial-security}
                              :shipment/handoff handoff}})]
          (testing ":shipment/handoff survives"
            (is (= handoff (:shipment/handoff (store/shipment s "ship-rt")))))
          (testing ":compliance-checklist survives as a set"
            (is (= #{:forwarder-registration :customs-broker-licence :financial-security}
                   (set (:compliance-checklist (store/shipment s "ship-rt")))))))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "shipment-record commit drafts a record and advances the shipment sequence"
        (store/commit-record! s {:effect :shipment/log :path ["ship-1"]})
        (is (= "JPN-SHIPMENT-000000" (get (first (store/shipment-log s)) "record_id")))
        (is (= "shipment-log-draft" (get (first (store/shipment-log s)) "kind")))
        (is (= 1 (count (store/shipment-log s))))
        (is (= 1 (store/next-sequence s "JPN" :shipment))))
      (testing "schedule-proposal commit drafts a record and advances the schedule sequence"
        (store/commit-record! s {:effect :schedule/propose :path ["ship-1"]})
        (is (= "JPN-SCHEDULE-000000" (get (first (store/schedule-log s)) "record_id")))
        (is (= 1 (count (store/schedule-log s))))
        (is (= 1 (store/next-sequence s "JPN" :schedule))))
      (testing "carrier-coordination commit drafts a record against a carrier"
        (store/commit-record! s {:effect :carrier/coordinate :path ["car-1"]})
        (is (= "JPN-CARRIER-000000" (get (first (store/carrier-log s)) "record_id")))
        (is (= 1 (count (store/carrier-log s)))))
      (testing "compliance-concern commit drafts a record"
        (store/commit-record! s {:effect :concern/record :path ["ship-1"]})
        (is (= "JPN-CONCERN-000000" (get (first (store/concern-log s)) "record_id")))
        (is (= 1 (count (store/concern-log s)))))
      (testing "a second shipment-record commit for the SAME jurisdiction advances the sequence"
        (store/commit-record! s {:effect :shipment/log :path ["ship-2"]})
        (is (= 2 (count (store/shipment-log s))))
        (is (= "JPN-SHIPMENT-000001" (get (second (store/shipment-log s)) "record_id")))
        (is (= 2 (store/next-sequence s "JPN" :shipment))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/shipment s "nope")))
    (is (nil? (store/carrier s "nope")))
    (is (= [] (store/all-shipments s)))
    (is (= [] (store/all-carriers s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/shipment-log s)))
    (is (zero? (store/next-sequence s "JPN" :shipment)))
    (store/with-shipments s {"x" {:id "x" :shipper-name "Test Shipper" :destination "USLAX"
                                  :jurisdiction "JPN" :registered? true :verified? true}})
    (is (= "Test Shipper" (:shipper-name (store/shipment s "x"))))
    (store/with-carriers s {"y" {:id "y" :name "Test Carrier" :mode "road"
                                 :jurisdiction "JPN" :registered? true}})
    (is (= "Test Carrier" (:name (store/carrier s "y"))))))
