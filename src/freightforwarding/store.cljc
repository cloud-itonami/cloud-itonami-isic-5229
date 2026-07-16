(ns freightforwarding.store
  "SSoT for the freight-forwarding/customs-brokerage operations-
  coordination actor (ISIC 5229), behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/freightforwarding/store_contract_test.clj), which is the whole
  point: the actor, the Freight Forwarding Governor and the audit
  ledger never know which SSoT they run on.

  Two entity directories:
    - `shipment` -- a per-consignment shipment/client record (an
                    independently registered + verified customs-
                    compliance scope entry for one shipment). Targeted
                    by `:log-shipment-record`, `:schedule-logistics-
                    operation` and `:flag-compliance-concern`.
    - `carrier`  -- a carrier/client account-relationship record (the
                    counterparty a booking is coordinated with, NOT a
                    per-shipment record). Targeted by
                    `:coordinate-carrier-booking` (a facility-level op,
                    the same 'facility-level ops don't need a
                    per-target verification of the SAME kind of
                    record' exemption `cloud-itonami-isic-561`'s
                    governor establishes for its own non-reservation
                    ops -- here it independently re-verifies the
                    target CARRIER is :registered? instead of a
                    per-shipment clearance).

  This actor is deliberately an OPERATIONS COORDINATION layer, not a
  customs-clearance/shipment-release authority: every commit below is
  a LOG / PROPOSAL / COORDINATION record, never a customs-clearance
  decision or a shipment-release authorization -- see
  `freightforwarding.governor`'s `finalize-clearance-violations` (a
  hard, permanent block) and the closed op-allowlist, which together
  make 'directly finalize a customs clearance or release a shipment'
  structurally unreachable from this actor.

  The ledger stays append-only on every backend: which shipment/
  carrier was screened, which proposal committed or held, and on what
  basis, is always a query over an immutable log -- the audit trail a
  freight forwarder, customs broker or regulator trusts this actor
  with."
  (:require [freightforwarding.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (shipment [s id])
  (all-shipments [s])
  (carrier [s id])
  (all-carriers [s])
  (ledger [s])
  (shipment-log [s] "the append-only committed :log-shipment-record history")
  (schedule-log [s] "the append-only committed :schedule-logistics-operation history")
  (carrier-log [s] "the append-only committed :coordinate-carrier-booking history")
  (concern-log [s] "the append-only committed :flag-compliance-concern history (post human sign-off)")
  (next-sequence [s jurisdiction kind] "next record-number sequence for a jurisdiction + record kind")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-shipments [s shipments] "replace/seed the shipment directory (map id->shipment)")
  (with-carriers [s carriers] "replace/seed the carrier directory (map id->carrier)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained shipment + carrier set covering the happy
  path plus the governor's own hard checks, so the actor + tests run
  offline. Each violation entity isolates exactly ONE failure mode
  (the rest stay clean), the 'exercise the failure mode directly,
  never only via a happy-path actuation' discipline every sibling
  governor's demo data establishes."
  []
  {:shipments
   {"ship-1" {:id "ship-1" :shipper-name "Acme Trading Co" :destination "NLRTM"
              :jurisdiction "JPN" :registered? true :verified? true}
    "ship-2" {:id "ship-2" :shipper-name "Borealis Imports" :destination "USLAX"
              :jurisdiction "JPN" :registered? true :verified? false}
    "ship-3" {:id "ship-3" :shipper-name "Cascade Exports" :destination "DEHAM"
              :jurisdiction "JPN" :registered? false :verified? false}}
   :carriers
   {"car-1" {:id "car-1" :name "Pacific Ocean Line" :mode "ocean"
             :jurisdiction "JPN" :registered? true}
    "car-2" {:id "car-2" :name "Unverified Air Cargo" :mode "air"
             :jurisdiction "JPN" :registered? false}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- commit-kind!
  "Backend-agnostic record draft for `kind` (:shipment | :schedule |
  :carrier | :concern) -- drafts the append-only record for
  `target-id`/`jurisdiction`/`seq-n` and returns {:result ..} for the
  caller to persist. Pure w.r.t. any particular backend's transaction
  mechanics (the backend has already resolved `jurisdiction` and
  `seq-n` via the protocol before calling this)."
  [kind target-id jurisdiction seq-n]
  (let [result (case kind
                 :shipment (registry/register-shipment-record target-id jurisdiction seq-n)
                 :schedule (registry/register-schedule-record target-id jurisdiction seq-n)
                 :carrier  (registry/register-carrier-coordination-record target-id jurisdiction seq-n)
                 :concern  (registry/register-concern-record target-id jurisdiction seq-n))]
    {:result result}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (shipment [_ id] (get-in @a [:shipments id]))
  (all-shipments [_] (sort-by :id (vals (:shipments @a))))
  (carrier [_ id] (get-in @a [:carriers id]))
  (all-carriers [_] (sort-by :id (vals (:carriers @a))))
  (ledger [_] (:ledger @a))
  (shipment-log [_] (:shipment-log @a))
  (schedule-log [_] (:schedule-log @a))
  (carrier-log [_] (:carrier-log @a))
  (concern-log [_] (:concern-log @a))
  (next-sequence [_ jurisdiction kind] (get-in @a [:sequences kind jurisdiction] 0))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :shipment/upsert
      (swap! a update-in [:shipments (:id value)] merge value)

      :carrier/upsert
      (swap! a update-in [:carriers (:id value)] merge value)

      (:shipment/log :schedule/propose :carrier/coordinate :concern/record)
      (let [kind (case effect
                   :shipment/log :shipment
                   :schedule/propose :schedule
                   :carrier/coordinate :carrier
                   :concern/record :concern)
            target-id (first path)
            jurisdiction (or (:jurisdiction (shipment s target-id))
                             (:jurisdiction (carrier s target-id)))
            seq-n (next-sequence s jurisdiction kind)
            {:keys [result]} (commit-kind! kind target-id jurisdiction seq-n)
            log-key (case kind
                      :shipment :shipment-log :schedule :schedule-log
                      :carrier :carrier-log :concern :concern-log)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences kind jurisdiction] (fnil inc 0))
                       (update log-key registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-shipments [s shipments] (when (seq shipments) (swap! a assoc :shipments shipments)) s)
  (with-carriers [s carriers] (when (seq carriers) (swap! a assoc :carriers carriers)) s))

(defn seed-db
  "A MemStore seeded with the demo shipment/carrier set. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :sequences {}
                           :shipment-log [] :schedule-log []
                           :carrier-log [] :concern-log []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

;; Schema, the EDN-blob codec and the shipment/carrier entity
;; map<->tx<->pull are the shared kotoba-lang/langchain-store machinery
;; (ADR-2607141600) -- the seam ~190 actors hand-roll. Only the
;; shipment/carrier field specs and the ledger/log/sequence attrs
;; (custom query shapes) are per-domain wiring.
(def ^:private schema
  (ls/identity-schema [:shipment/id :carrier/id :ledger/seq
                       :shipment-log/seq :schedule/seq :carrier-log/seq :concern/seq
                       :sequence/key]))

(defn- enc [v] (ls/enc v))
(defn- dec* [s] (ls/dec* s))

(def ^:private shipment-spec
  {:id {:attr :shipment/id}
   :shipper-name {:attr :shipment/shipper-name}
   :destination {:attr :shipment/destination}
   :jurisdiction {:attr :shipment/jurisdiction}
   :registered? {:attr :shipment/registered? :coerce boolean}
   :verified? {:attr :shipment/verified? :coerce boolean}})

(def ^:private carrier-spec
  {:id {:attr :carrier/id}
   :name {:attr :carrier/name}
   :mode {:attr :carrier/mode}
   :jurisdiction {:attr :carrier/jurisdiction}
   :registered? {:attr :carrier/registered? :coerce boolean}})

(defn- shipment->tx [m] (ls/map->tx shipment-spec m))
(def ^:private shipment-pull (ls/pull-pattern shipment-spec))
(defn- pull->shipment [m] (ls/pull->map shipment-spec :id m))

(defn- carrier->tx [m] (ls/map->tx carrier-spec m))
(def ^:private carrier-pull (ls/pull-pattern carrier-spec))
(defn- pull->carrier [m] (ls/pull->map carrier-spec :id m))

(defn- seq-key [jurisdiction kind] (str (name kind) "::" jurisdiction))

(defrecord DatomicStore [conn]
  Store
  (shipment [_ id]
    (pull->shipment (d/pull (d/db conn) shipment-pull [:shipment/id id])))
  (all-shipments [_]
    (->> (d/q '[:find [?id ...] :where [?e :shipment/id ?id]] (d/db conn))
         (map #(pull->shipment (d/pull (d/db conn) shipment-pull [:shipment/id %])))
         (sort-by :id)))
  (carrier [_ id]
    (pull->carrier (d/pull (d/db conn) carrier-pull [:carrier/id id])))
  (all-carriers [_]
    (->> (d/q '[:find [?id ...] :where [?e :carrier/id ?id]] (d/db conn))
         (map #(pull->carrier (d/pull (d/db conn) carrier-pull [:carrier/id %])))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (shipment-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :shipment-log/seq ?s] [?e :shipment-log/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp dec* second))))
  (schedule-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :schedule/seq ?s] [?e :schedule/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp dec* second))))
  (carrier-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :carrier-log/seq ?s] [?e :carrier-log/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp dec* second))))
  (concern-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :concern/seq ?s] [?e :concern/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction kind]
    (or (d/q '[:find ?n . :in $ ?k
              :where [?e :sequence/key ?k] [?e :sequence/next ?n]]
            (d/db conn) (seq-key jurisdiction kind))
        0))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :shipment/upsert
      (d/transact! conn [(shipment->tx value)])

      :carrier/upsert
      (d/transact! conn [(carrier->tx value)])

      (:shipment/log :schedule/propose :carrier/coordinate :concern/record)
      (let [kind (case effect
                   :shipment/log :shipment
                   :schedule/propose :schedule
                   :carrier/coordinate :carrier
                   :concern/record :concern)
            target-id (first path)
            jurisdiction (or (:jurisdiction (shipment s target-id))
                             (:jurisdiction (carrier s target-id)))
            seq-n (next-sequence s jurisdiction kind)
            {:keys [result]} (commit-kind! kind target-id jurisdiction seq-n)
            next-n (inc seq-n)
            seq-attr (case kind :shipment :shipment-log/seq :schedule :schedule/seq
                          :carrier :carrier-log/seq :concern :concern/seq)
            rec-attr (case kind :shipment :shipment-log/record :schedule :schedule/record
                          :carrier :carrier-log/record :concern :concern/record)
            log-count (case kind
                        :shipment (count (shipment-log s)) :schedule (count (schedule-log s))
                        :carrier (count (carrier-log s)) :concern (count (concern-log s)))]
        (d/transact! conn
                     [{:sequence/key (seq-key jurisdiction kind) :sequence/next next-n}
                      {seq-attr log-count rec-attr (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-shipments [s shipments]
    (when (seq shipments) (d/transact! conn (mapv shipment->tx (vals shipments)))) s)
  (with-carriers [s carriers]
    (when (seq carriers) (d/transact! conn (mapv carrier->tx (vals carriers)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:shipments .. :carriers ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [shipments carriers]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-shipments shipments) (with-carriers carriers)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo shipment/carrier set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
