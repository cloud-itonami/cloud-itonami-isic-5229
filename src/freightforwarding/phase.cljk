(ns freightforwarding.phase
  "Phase 0->3 staged rollout for the freight-forwarding/customs-
  brokerage operations-coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-logging     -- shipment-record logging and
                                      compliance-concern flagging
                                      allowed, every write needs human
                                      approval.
    Phase 2  assisted-scheduling  -- adds routing/consolidation
                                      scheduling proposal writes,
                                      still approval.
    Phase 3  supervised-auto      -- governor-clean, high-confidence
                                      `:log-shipment-record` (pure
                                      administrative logging, no
                                      capital risk, no customs-
                                      clearance determination) may
                                      auto-commit. `:schedule-
                                      logistics-operation` and
                                      `:coordinate-carrier-booking`
                                      (real resource commitments --
                                      routing/consolidation assignment,
                                      carrier-booking dispatch) ALWAYS
                                      need human approval, even when
                                      governor-clean, at every phase
                                      including 3.

  `:flag-compliance-concern` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Surfacing a reported customs-
  documentation discrepancy or regulatory-compliance gap ALWAYS
  reaches a human; this actor never itself acts on the concern
  (`freightforwarding.governor`'s `high-stakes` set enforces the same
  invariant independently -- two layers, not one, agree on this).
  Likewise, no op that would finalize a customs-clearance or
  shipment-release decision exists ANYWHERE in this domain's op set at
  all (see `freightforwarding.governor`'s closed op-allowlist +
  `finalize-clearance-violations`), so there is no entry to
  accidentally add to `:auto` in the first place -- the strongest
  possible form of 'never auto-commit-eligible'.")

(def read-ops  #{})
(def write-ops #{:log-shipment-record :schedule-logistics-operation
                 :flag-compliance-concern :coordinate-carrier-booking})

;; NOTE the invariant: `:flag-compliance-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there. Likewise no op
;; that finalizes a customs-clearance/shipment-release decision exists
;; in `write-ops` at all -- see the namespace docstring.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"           :writes #{}                                                                       :auto #{}}
   1 {:label "assisted-logging"    :writes #{:log-shipment-record :flag-compliance-concern}                          :auto #{}}
   2 {:label "assisted-scheduling" :writes #{:log-shipment-record :flag-compliance-concern :schedule-logistics-operation} :auto #{}}
   3 {:label "supervised-auto"     :writes write-ops
      :auto #{:log-shipment-record}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-compliance-concern` is never auto-eligible at any phase, so
    it always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Freight Forwarding Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
