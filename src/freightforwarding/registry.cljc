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

;; ─────── Inbound Cross-Actor Handoff (this actor as RECEIVER, isic-5210 -> isic-5229) ───────
;;
;; A `:log-shipment-record` shipment MAY OPTIONALLY carry a nested
;; `:shipment/handoff` record: the SAME `:handoff/*` wire shape every
;; sibling `cloud-itonami-isic-*` actor in this fleet already uses --
;; zero shared code, zero shared dependency (this repo has no
;; `facts.cljc`, so this predicate is pasted here verbatim, mirroring
;; `cloud-itonami-isic-1702`'s own `corrugated.registry` placement
;; choice). See superproject ADR-2607177600 / ADR-2800000500 /
;; ADR-2800000700 / ADR-2800000800 / ADR-2800002100. This actor is the
;; RECEIVING side: an upstream storage/terminal actor (e.g.
;; `cloud-itonami-isic-5210`) MAY attach a `:handoff` when it
;; transfers custody of stock to this freight-forwarding/customs-
;; brokerage actor's care -- purely an OPTIONAL provenance/audit-trail
;; attachment establishing where a shipment's goods originated, never
;; itself a customs-clearance or shipment-release decision. Attaching
;; a `:handoff` is entirely optional: every existing shipment record
;; worked before this field existed and keeps working unchanged with
;; no `:handoff` attached at all -- absence is never flagged.
;;
;;   {:shipment/handoff
;;    {:handoff/id "..."
;;     :handoff/source-actor "cloud-itonami-isic-5210"
;;     :handoff/batch-id "..."
;;     :handoff/product-type-id :some/keyword-or-string
;;     :handoff/quantity-kg 120.5
;;     :handoff/dispatched-at-iso "2026-07-24T00:00:00Z"
;;     ;; OPTIONAL pass-through, never validated for well-formedness:
;;     :handoff/cold-chain-temp-min-c 2.0
;;     :handoff/cold-chain-temp-max-c 10.0
;;     :handoff/unspsc-code "..."
;;     :handoff/gtin "..."
;;     :handoff/carrier-actor "cloud-itonami-isic-4920"
;;     :handoff/carrier-tracking-ref "..."}}

(defn handoff-record-well-formed?
  "Positive-sense convenience predicate: does `handoff` carry every
  REQUIRED `:handoff/*` field (id/source-actor/batch-id/product-type-id/
  quantity-kg/dispatched-at-iso) with a plausible value (quantity-kg a
  positive number, the string fields non-blank)? Never validates the
  OPTIONAL cold-chain/unspsc/gtin/carrier pass-through fields."
  [handoff]
  (boolean
   (and (map? handoff)
        (seq (:handoff/id handoff))
        (seq (:handoff/source-actor handoff))
        (seq (:handoff/batch-id handoff))
        (some? (:handoff/product-type-id handoff))
        (number? (:handoff/quantity-kg handoff))
        (pos? (:handoff/quantity-kg handoff))
        (seq (:handoff/dispatched-at-iso handoff)))))

(def storage-handoff-source-actors
  "Which upstream cloud-itonami actors this freight-forwarding actor
  recognizes as a legitimate :handoff/source-actor for a shipment's
  documented origin (ADR-2800002100)."
  #{"cloud-itonami-isic-5210"})

(defn storage-handoff-source-actor-known?
  "Positive-sense convenience predicate: is `source-actor` one of this
  actor's actually-registered upstream storage/terminal suppliers
  (`storage-handoff-source-actors`)? A nil source-actor always
  returns false -- absence is never silently treated as known."
  [source-actor]
  (boolean (contains? storage-handoff-source-actors source-actor)))
