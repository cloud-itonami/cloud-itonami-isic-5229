(ns freightforwarding.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean shipment through
  shipment-record logging (auto-commits at phase 3) -> routing/
  consolidation scheduling (escalates/approve/commit) -> a compliance-
  concern flag (ALWAYS escalates/approve/commit) -> carrier-booking
  coordination (escalates/approve/commit), then shows HARD-hold
  scenarios: an unverified shipment, an unregistered shipment, an
  unregistered carrier, a hallucinated (non-allowlisted) op, and a
  proposal whose text tries to finalize a customs clearance
  (scope-exclusion hard block).

  Each check is exercised directly and independently, one shipment/
  carrier per HARD-hold scenario, the same 'exercise the failure mode
  directly, never only via a happy-path actuation' discipline every
  sibling actor's sim establishes."
  (:require [langgraph.graph :as g]
            [freightforwarding.store :as store]
            [freightforwarding.operation :as op]
            [freightforwarding.governor :as governor]))

(def operator {:actor-id "op-1" :actor-role :freight-forwarding-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== log-shipment-record ship-1 (clean, JPN -- auto-commits at phase 3) ==")
    (println (exec-op actor "t1" {:op :log-shipment-record :target-id "ship-1"
                                  :detail "customs invoice received"} operator))

    (println "== schedule-logistics-operation ship-1 (escalates -- real resource commitment) ==")
    (let [r (exec-op actor "t2" {:op :schedule-logistics-operation :target-id "ship-1"
                                 :resource-request {:route "NLRTM" :consolidation "LCL"}} operator)]
      (println r)
      (println "-- human freight-forwarding operator approves --")
      (println (approve! actor "t2")))

    (println "== flag-compliance-concern ship-1 (ALWAYS escalates) ==")
    (let [r (exec-op actor "t3" {:op :flag-compliance-concern :target-id "ship-1"
                                 :concern-type "document-discrepancy"
                                 :description "HS code on commercial invoice does not match packing list"} operator)]
      (println r)
      (println "-- human freight-forwarding operator signs off --")
      (println (approve! actor "t3")))

    (println "== coordinate-carrier-booking car-1 (escalates -- real carrier commitment) ==")
    (let [r (exec-op actor "t4" {:op :coordinate-carrier-booking :target-id "car-1"
                                 :booking-request "40ft-container-space"} operator)]
      (println r)
      (println "-- human freight-forwarding operator approves --")
      (println (approve! actor "t4")))

    (println "== log-shipment-record ship-2 (unverified shipment -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :log-shipment-record :target-id "ship-2"
                                  :detail "packing list received"} operator))

    (println "== schedule-logistics-operation ship-3 (unregistered shipment -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :schedule-logistics-operation :target-id "ship-3"
                                  :resource-request {:route "DEHAM"}} operator))

    (println "== coordinate-carrier-booking car-2 (unregistered carrier -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :coordinate-carrier-booking :target-id "car-2"
                                  :booking-request "air-cargo-space"} operator))

    (println "== defense-in-depth: hallucinated op / smuggled finalize action (governor/check directly) ==")
    (println "hallucinated op not in closed allowlist:"
             (governor/check {:op :log-shipment-record :target-id "ship-1"}
                             operator
                             {:operation :finalize-customs-clearance :effect :propose
                              :target-id "ship-1" :confidence 0.9}
                             db))
    (println "proposal text smuggling a finalize-clearance action:"
             (governor/check {:op :schedule-logistics-operation :target-id "ship-1"}
                             operator
                             {:operation :schedule-logistics-operation :effect :propose
                              :target-id "ship-1" :confidence 0.9
                              :rationale "recommend to finalize the customs clearance now"}
                             db))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft shipment-log records ==")
    (doseq [r (store/shipment-log db)] (println r))

    (println "== draft schedule-log records ==")
    (doseq [r (store/schedule-log db)] (println r))

    (println "== draft concern-log records ==")
    (doseq [r (store/concern-log db)] (println r))

    (println "== draft carrier-log records ==")
    (doseq [r (store/carrier-log db)] (println r))))
