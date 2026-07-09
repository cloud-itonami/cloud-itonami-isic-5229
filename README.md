# cloud-itonami-5229

Open Business Blueprint for **ISIC Rev.5 5229**: other transportation
support activities (freight forwarding and customs brokerage).

This repository designs a forkable OSS business for community freight
forwarding: multi-carrier routing and customs-compliance-scope
management, robotics-assisted customs-document verification and
cross-dock staging, and consignment booking/reconciliation records —
run by a qualified operator so a freight forwarder or customs broker
keeps its own licensing and compliance history instead of renting a
closed freight-forwarding platform.

## Scope note: forwarding/brokerage, not carriage or terminal handling

`cloud-itonami-isic-4911`/`4912`/`4920`/`5110`/`5011`/`5020` are all
CARRIERS that move goods aboard their own vehicle or vessel.
`cloud-itonami-isic-5224` (cargo handling) is a terminal SERVICE that
physically loads and unloads cargo. This repository is deliberately
scoped to the SEPARATE business of freight forwarding and customs
brokerage: an intermediary that ARRANGES carriage across multiple
carriers and modes on a shipper's behalf and clears goods through
customs, without owning transport assets or operating a terminal. This
is a distinctly licensed profession in every jurisdiction: the US
requires a Customs Broker License under 19 CFR Part 111; Japan's
通関業法 (Customs Business Act) licenses 通関士 (customs specialists)
separately from carriers and terminal operators; the EU's Union
Customs Code requires Authorised Economic Operator / customs
representative status; freight forwarders in most jurisdictions carry
their own professional liability and bonding requirements distinct
from carrier cargo liability (e.g. FIATA-model forwarder liability
terms).

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (customs-document
scanning and verification, cross-dock staging and consolidation)
operate under an actor that proposes actions and an independent
**Freight Forwarding Governor** that gates them. The governor never
releases a customs declaration or dispatches a consolidated shipment
itself; `:high`/`:safety-critical` actions (a customs declaration
outside verified compliance scope, a consolidated shipment dispatched
without a completed document-verification pass, a reconciliation
record without verified evidence) require human sign-off.

## Core Contract

```text
intake + identity + customs-compliance scope + booking
        |
        v
Freight Forwarding Advisor -> Freight Forwarding Governor -> declaration record, dispatch, reconciliation record, or human approval
        |
        v
robot actions (gated) + document-verification record + reconciliation record + audit ledger
```

No automated advice can release a customs declaration the governor
refuses, dispatch a consolidated shipment outside its verified
compliance scope, or publish a reconciliation record without governor
approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `5229`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics) — booking, transit, delivery/reconciliation contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
