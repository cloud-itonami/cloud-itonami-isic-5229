(ns freightforwarding.governor
  "Freight Forwarding Governor (`:itonami.blueprint/governor
  :freight-forwarding-governor`, matching this repo's own
  `blueprint.edn` -- grep-verified unique within this repo) -- the
  independent compliance layer that earns the FreightForwardingAdvisor
  the right to commit. The LLM has no notion of whether a shipment/
  client record is actually independently registered and verified,
  whether a proposal actually stays inside the closed coordination-op
  allowlist, whether a proposal's own text quietly tries to finalize a
  customs-clearance or shipment-release decision, or when a
  compliance concern must escalate rather than commit, so this MUST be
  a separate system able to *reject* a proposal and fall back to
  HOLD.

  This is an OPERATIONS COORDINATION actor, not a customs-clearance/
  shipment-release authority -- it never itself finalizes a customs-
  clearance decision, waives a customs-inspection requirement, or
  authorizes a shipment release. Four checks, in priority order, ALL
  HARD violations (a human approver CANNOT override them):

    1. Shipment/carrier record unverified -- the target shipment
       (`:log-shipment-record`, `:schedule-logistics-operation`,
       `:flag-compliance-concern`) must be independently :registered?
       AND :verified? in the store, re-derived from the REQUEST every
       time, never from the proposal's self-report.
       `:coordinate-carrier-booking` is carrier-level (the same
       'facility-level ops don't need per-target verification of the
       SAME kind of record' exemption `cloud-itonami-isic-561`'s
       governor establishes for its own non-reservation ops) -- it
       independently re-verifies the target CARRIER is :registered?
       instead.
    2. Effect not :propose         -- rejected outright, whatever the
                                       advisor claims.
    3. Closed op-allowlist         -- the proposal's own :operation
                                       must be one of the four allowed
                                       ops; anything else (including a
                                       hallucinated op) is a hard,
                                       permanent block.
    4. Finalize-clearance scope
       exclusion                    -- ANY proposal whose text tries to
                                       finalize a customs-clearance
                                       decision, waive a customs-
                                       inspection requirement, or
                                       authorize a shipment release is
                                       a hard, permanent block -- this
                                       territory structurally does not
                                       exist as an op in this actor at
                                       all (see closed op-allowlist
                                       above), and this check catches
                                       any attempt to smuggle a
                                       finalization ACTION into an
                                       otherwise-legitimate proposal's
                                       own text.

  ONE known self-tripping bug class this check is written to AVOID
  (multiple sibling `cloud-itonami-isic-*` actors in this fleet
  independently hit and fixed the SAME bug): phrasing an exclusion
  term as a bare noun ('clearance', 'customs', 'release') makes it
  match inside the mock advisor's own DEFAULT rationale/disclaimer
  text for a legitimate, allowed proposal -- e.g. a
  `:flag-compliance-concern` proposal's own honest rationale
  legitimately says the words 'customs-clearance' and 'shipment-
  release' as NOUNS, so a bare-noun pattern list would self-block the
  actor's own happy path. Every pattern below is phrased as the
  FINALIZATION/EXECUTION ACTION ('finalize the customs clearance',
  'authorize the shipment release'), never the bare noun --
  `test/freightforwarding/scope_exclusion_test.clj` asserts directly
  that every one of `freightforwarding.advisor`'s four default
  proposals passes this check cleanly.

  The confidence/escalation gate is SOFT: it asks a human to look (low
  confidence, or `:flag-compliance-concern` which is ALWAYS
  high-stakes) -- see `freightforwarding.phase` for the
  belt-and-suspenders second layer: `:flag-compliance-concern` is
  never a member of any phase's `:auto` set either.

  ADDITIVE SOFT gate (never a hold, escalates only): a
  `:log-shipment-record` shipment's OPTIONAL `:shipment/handoff` --
  present only when an upstream storage/terminal actor (e.g.
  `cloud-itonami-isic-5210`) attached custody-transfer provenance
  data when handing off stock -- that is present but structurally
  malformed, or that names a `:handoff/source-actor` this actor does
  not recognize, forces `:escalate?` true (`:storage-handoff-
  suspect`, ADR-2800002100). Absence of a `:handoff` is completely
  normal and NEVER flagged -- see `freightforwarding.registry`'s
  'Inbound Cross-Actor Handoff' section for the shared wire shape
  (zero shared code/dependency with the sender) and this actor's own
  recognized-source-actor roster."
  (:require [freightforwarding.store :as store]
            [freightforwarding.registry :as registry]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed op-allowlist. Anything else is a hard, permanent block
  (check 3)."
  #{:log-shipment-record
    :schedule-logistics-operation
    :flag-compliance-concern
    :coordinate-carrier-booking})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Flagging a compliance concern always escalates to human sign-off --
  never in any phase's :auto set either (belt and suspenders,
  `freightforwarding.phase`)."
  #{:flag-compliance-concern})

(def facility-level-ops
  "Ops whose target is a `carrier` record, not a per-shipment
  `shipment` record -- exempt from shipment-verification (check 1
  instead independently re-verifies the target CARRIER is
  :registered?)."
  #{:coordinate-carrier-booking})

;; ----------------------------- checks -----------------------------

(defn- shipment-unverified-violations
  "Check 1: re-derived from the store, never from proposal
  self-report. Carrier-level ops (`:coordinate-carrier-booking`)
  independently re-verify the target CARRIER is :registered? instead
  of a per-shipment record."
  [{:keys [op target-id]} st]
  (if (contains? facility-level-ops op)
    (let [c (store/carrier st target-id)]
      (when-not (and c (:registered? c))
        [{:rule :carrier-unregistered
          :detail (str target-id " は登録済みcarrierとして独立検証できません")}]))
    (let [s (store/shipment st target-id)]
      (cond
        (nil? s)
        [{:rule :shipment-unverified
          :detail (str target-id " のshipment記録が見つかりません")}]

        (not (:registered? s))
        [{:rule :shipment-unverified
          :detail (str target-id " のshipmentは未登録です")}]

        (not (:verified? s))
        [{:rule :shipment-unverified
          :detail (str target-id " のshipmentは未検証です")}]

        :else []))))

(defn- effect-not-propose-violations
  "Check 2: the proposal's own :effect must be :propose, whatever the
  advisor claims elsewhere."
  [proposal]
  (when (not= (:effect proposal) :propose)
    [{:rule :effect-not-propose
      :detail (str "effectが" (:effect proposal) "であり:proposeではありません")}]))

(defn- closed-allowlist-violations
  "Check 3: the proposal's own :operation must be one of the four
  allowed coordination ops. Anything else -- including a hallucinated
  op a real LLM advisor might propose -- is a hard, permanent block."
  [proposal]
  (when-not (contains? allowed-ops (:operation proposal))
    [{:rule :op-not-allowed
      :detail (str (:operation proposal) " はクローズドop許可リストに含まれません")}]))

(def ^:private finalize-clearance-patterns
  "Forbidden FINALIZATION/EXECUTION ACTION phrases -- phrased as the
  action, never a bare noun, so a legitimate proposal's own honest
  rationale (which may legitimately mention 'customs clearance',
  'shipment release', 'customs declaration' etc. as NOUNS) never
  self-trips this check. See the namespace docstring's
  self-tripping-bug note."
  [;; EN -- verb + customs-clearance/shipment-release/inspection object
   #"(?i)finalize\s+(the\s+)?customs[\w\s-]*clearance"
   #"(?i)finalize\s+(the\s+)?shipment[\w\s-]*release"
   #"(?i)authorize\s+(the\s+)?shipment[\w\s-]*release"
   #"(?i)release\s+the\s+shipment\s+from\s+customs"
   #"(?i)clear\s+(the\s+)?goods\s+through\s+customs"
   #"(?i)waive\s+(the\s+)?customs[\w\s-]*inspection"
   #"(?i)declare\s+(the\s+)?shipment[\w\s-]*released"
   #"(?i)grant\s+(the\s+)?customs[\w\s-]*clearance"
   #"(?i)issue\s+(the\s+)?customs[\w\s-]*clearance"
   ;; JA -- same discipline: action verb + object, never a bare noun
   #"通関.{0,6}確定"
   #"荷物.{0,6}リリース.{0,6}(確定|許可)"
   #"税関.{0,6}検査.{0,6}(免除|解除)"
   #"貨物.{0,6}引取.{0,6}許可"])

(defn- finalize-clearance-violations
  "Check 4: scan the proposal's own text for a smuggled finalization
  ACTION. Structurally unreachable via the closed op-allowlist alone
  (check 3) since no such op exists at all -- this check is
  defense-in-depth against a real LLM advisor embedding a
  finalization claim inside an otherwise-legitimate proposal's
  :summary/:rationale text."
  [proposal]
  (let [text (pr-str proposal)]
    (when (some #(re-find % text) finalize-clearance-patterns)
      [{:rule :finalize-clearance-attempt
        :detail "提案テキストが通関クリアランス/貨物リリースの確定アクションを含んでいます -- 恒久ブロック"}])))

;; ────────────── Inbound Cross-Actor Handoff (Escalate, not Hold, isic-5210 -> isic-5229) ──────────────

(defn- storage-handoff-suspect-escalation
  "SOFT -- only when a `:shipment/handoff` map is actually present on
  the target shipment's own store record. Absence is never flagged
  (attaching a `:handoff` is entirely optional -- see
  `freightforwarding.registry`'s 'Inbound Cross-Actor Handoff'
  section). Escalates (never holds) when present-but-malformed or
  from an unrecognized source-actor (ADR-2800002100)."
  [{:keys [op target-id]} st]
  (when (= op :log-shipment-record)
    (let [s (store/shipment st target-id)
          handoff (:shipment/handoff s)]
      (when (map? handoff)
        (when-not (and (registry/handoff-record-well-formed? handoff)
                       (registry/storage-handoff-source-actor-known?
                        (:handoff/source-actor handoff)))
          [{:rule :storage-handoff-suspect
            :detail (str target-id " のshipmentに添付された:shipment/handoff(source-actor="
                         (pr-str (:handoff/source-actor handoff))
                         ")が構造不整合、または登録済みsource-actorと一致しません -- "
                         "holdではなく人間へのescalateが必要(ADR-2800002100)")}])))))

;; ----------------------------- decision logic -----------------------------

(defn check
  "Censors a FreightForwardingAdvisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :soft-violations [..]
  :confidence c :escalate? bool :high-stakes? bool :hard? bool}.

  `:violations` (`hard`) are the four un-overridable HOLD checks
  above -- unchanged, still meaning exactly what they always have.
  `:soft-violations` is an additional, independently-detected concern
  (`:storage-handoff-suspect`) that is NOT grounds for a hold but DOES
  force `:escalate?` true even when every hard check passes,
  confidence is high, and the op is not otherwise high-stakes -- same
  effect as low confidence, kept in a separate key so `:violations`
  keeps its original meaning."
  [request _context proposal st]
  (let [hard (into []
                   (concat (shipment-unverified-violations request st)
                           (effect-not-propose-violations proposal)
                           (closed-allowlist-violations proposal)
                           (finalize-clearance-violations proposal)))
        soft (into [] (concat (storage-handoff-suspect-escalation request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:op request)))
        hard? (boolean (seq hard))
        soft? (boolean (seq soft))]
    {:ok?          (and (not hard?) (not low?) (not stakes?) (not soft?))
     :violations   hard
     :soft-violations soft
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes? soft?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :target-id  (:target-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
