# ADR-0001 — the governor gains a spec-basis, and a soft gate that could never fire is fixed

- **Status**: accepted
- **Date**: 2026-08-06
- **Extends**: ADR-2800002100 (the inbound cross-actor handoff soft gate)

## Context

This actor coordinates freight forwarding and customs brokerage (ISIC 5229). It
is deliberately *not* a clearance authority — its closed op-allowlist has four
coordination ops, and a separate hard check refuses any proposal whose own text
tries to smuggle in a finalization action. That containment was already good.

Two things were missing.

**1. There was no spec-basis.** The actor had no `facts` namespace at all — the
only cloud-itonami warehouse-chain actor without one. Freight forwarding and
customs brokerage are *licensed* activities in essentially every jurisdiction,
and the licence is what separates a coordination act from an unlawful one. Yet
the governor would happily create an official coordination record against any
jurisdiction string whatsoever, including one it had never heard of. Every
sibling actor's governor asks "did the advisor cite an official source, or
invent one?"; this one could not ask, because there was nothing to cite.

The fleet maturity scan measured the consequence directly: `ingest` **0 bp**,
`:facts` absent from `:component/present`, `:doc/adr-count` **0** — the weakest
`M_own` (0.532) of the whole warehouse chain, at the chain's *entrance*.

**2. A soft gate had been silently dead on one backend.** While wiring the new
check, the store contract test caught something older: `:shipment/handoff` was
never in the Datomic `shipment-spec`. On `MemStore` the field round-tripped
fine; on `DatomicStore` it was dropped on write and read back as `nil`, so
`:storage-handoff-suspect` — the entire subject of ADR-2800002100 — **could
never fire there**. Nothing detected this, because the existing parity tests
only compared scalar fields.

## Decision

**Add `freightforwarding.facts`**, a per-jurisdiction forwarding/brokerage
regulatory catalog covering JPN, USA, EUR, GBR and KOR, each with its regulator,
its legal basis, its forwarder and broker regimes, the licensing evidence set
(`:forwarder-registration` / `:customs-broker-licence` / `:financial-security`),
and verified source URLs.

**Add HARD check 5, `:no-spec-basis`.** The target record's own `:jurisdiction`
— re-derived from the *store*, never from the proposal's self-report — must be
one the catalog has a registered regime for. This applies to the carrier-level
op too, not only to shipments. An unknown, missing or blank jurisdiction has no
spec-basis; absence of a rule is not permission.

Check 5 deliberately **does not** short-circuit when check 1 already fired. The
two answer different questions ("is this record verified here?" vs "do we know
this jurisdiction's regime at all?"), and a governor that reports only the first
violation it finds teaches the operator to fix one thing at a time.

**Add a second SOFT gate, `:licence-evidence-incomplete`**, following exactly
the shape ADR-2800002100 established: a target carrying an *optional*
`:compliance-checklist` that is present but short of its jurisdiction's required
evidence escalates to a human. It never holds. Absence of a checklist is never
flagged — most coordination records carry none, and holding the common case
would make the actor useless. But an operator who bothered to attach one and
left it short is saying something a human should read.

**Add `:compliance-checklist` and `:shipment/handoff` to the Datomic field spec**
as `:blob?` fields, and assert in the store contract that both round-trip on
*both* backends. A governor gate must not behave differently depending on which
SSoT is configured.

## Verification

- **55 tests / 317 assertions, 0 failures** (`clojure -M:dev:test`), up from
  37/210. The pre-existing 37 still pass unchanged except for the demo-set
  membership assertions, which now include the two new fixtures.
- **Mutation-tested.** Six independent breaks each turn the suite red, green on
  revert:

  | break | result |
  |---|---|
  | check 5 disabled | 8 failures |
  | blank/nil jurisdiction accepted as known | 1 error |
  | licence-evidence soft gate disabled | 4 failures |
  | unknown jurisdiction satisfies its evidence | 2 failures |
  | Datomic spec drops `:compliance-checklist` | 2 failures |
  | Datomic spec drops `:shipment/handoff` | 1 failure |

  The last two matter most: they prove the new parity tests actually catch the
  class of bug that hid `:shipment/handoff` for as long as it did.
- **Every URL in `freightforwarding.facts` was fetched and returned 2xx on
  2026-08-06.**
- **The two JPN law IDs were resolved against the e-Gov law API** and their
  `<LawTitle>` read back: `401AC0000000082` = 貨物利用運送事業法,
  `342AC0000000122` = 通関業法. This is asserted as verified because it was
  actually checked, not inferred from the ID format.

## Honest limits

- **`:provenance` claims reachability, not extraction.** Except for the two JPN
  law titles above, no clause, article or section number is asserted from any
  instrument. The catalog records the publisher page and the instrument's
  identity, which is what the spec-basis check needs and no more.
- **Five jurisdictions is a starting catalog, not the world.** `facts/coverage`
  says so explicitly. Singapore Customs (403) and the Australian Border Force
  broker-licensing page (404) were attempted and are absent rather than guessed.
- **A jurisdiction being absent from the catalog is not a claim that it has no
  regime.** It is a claim that *this actor* has no basis for it, which is why
  the correct response is HOLD rather than a compliance finding.
- **The soft gate reads a checklist someone else asserts.** It verifies the
  checklist covers the required set; it does not verify the licences exist. That
  is a document-verification problem this actor does not own.
- **Check 5 needs a record to read a jurisdiction from.** A target that does not
  exist at all is check 1's business, and check 5 stays silent for it rather
  than inventing a second violation.
