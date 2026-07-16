(ns freightforwarding.registry
  "Pure-function record construction for the freight-forwarding/
  customs-brokerage operations-coordination actor -- an append-only
  book-of-record draft for each of the four coordination ops.

  Like every sibling actor's registry, there is no single
  international reference-number standard for a shipment-log entry, a
  routing/consolidation scheduling proposal, a carrier-booking
  coordination record or a customs-compliance-concern record; every
  freight forwarder/customs broker assigns its own reference format.
  This namespace does NOT invent one beyond a jurisdiction-scoped
  sequence number; it drafts the record's required fields honestly,
  the same non-fabricating discipline every sibling registry uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real customs-single-window / carrier-booking / AEO
  system. It builds the RECORD an operator would keep, not a
  real-world act. Every record produced here is UNSIGNED and
  explicitly `:kind :*-draft` -- it is never, itself, a customs-
  clearance decision or a shipment-release authorization. That
  authority stays outside this actor entirely (see
  `freightforwarding.governor`'s `finalize-clearance-violations`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn- require-field! [op-label field-name v]
  (when-not (and v (not= v ""))
    (throw (ex-info (str op-label ": " field-name " required") {}))))

(defn- draft-record
  "Shared record-draft shape for all four coordination record kinds.
  `label` names the record kind (e.g. \"shipment-log\"); `target-id`
  is the shipment/carrier id the record concerns."
  [label prefix target-id jurisdiction sequence]
  (require-field! label "target_id" target-id)
  (require-field! label "jurisdiction" jurisdiction)
  (when (< sequence 0)
    (throw (ex-info (str label ": sequence must be >= 0") {})))
  (let [record-number (str (str/upper-case jurisdiction) "-" prefix "-" (zero-pad sequence 6))
        record {"record_id" record-number
                "kind" (str label "-draft")
                "target_id" target-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "record_number" record-number
     "certificate" (unsigned-certificate label record-number record-number)}))

(defn register-shipment-record
  "Draft a freight-forwarding/customs-documentation SHIPMENT-LOG entry
  -- administrative record-keeping only, never a customs-clearance
  decision."
  [shipment-id jurisdiction sequence]
  (draft-record "shipment-log" "SHIPMENT" shipment-id jurisdiction sequence))

(defn register-schedule-record
  "Draft a routing/consolidation SCHEDULING PROPOSAL -- a proposal
  only, never a shipment-release authorization."
  [shipment-id jurisdiction sequence]
  (draft-record "schedule-proposal" "SCHEDULE" shipment-id jurisdiction sequence))

(defn register-carrier-coordination-record
  "Draft a CARRIER-BOOKING coordination record -- coordination only,
  never a carrier-contract commitment or a shipment-release
  authorization."
  [carrier-id jurisdiction sequence]
  (draft-record "carrier-coordination" "CARRIER" carrier-id jurisdiction sequence))

(defn register-concern-record
  "Draft the record of a CUSTOMS-DOCUMENTATION/REGULATORY-COMPLIANCE
  CONCERN flag, written only after mandatory human sign-off -- the
  flag itself, never a customs-clearance or shipment-release status
  change."
  [shipment-id jurisdiction sequence]
  (draft-record "compliance-concern" "CONCERN" shipment-id jurisdiction sequence))

(defn append [history result]
  (conj (vec history) (get result "record")))
