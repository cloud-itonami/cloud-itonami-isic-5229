# Business Model: Community Freight Forwarding and Customs Brokerage

## Classification
- Repository: `cloud-itonami-5229`
- ISIC Rev.5: `5229` — other transportation support activities
- Social impact: supply-chain resilience, trade compliance,
  warehouse-worker safety

## Customer
- independent/community freight forwarders needing an auditable
  multi-carrier routing and compliance platform
- licensed customs brokers needing verifiable declaration and
  document-verification records
- shippers needing verifiable consignment, routing and reconciliation
  records across multiple carriers
- customs authorities and regulators needing verifiable compliance-
  scope and declaration records
- programs that cannot accept closed, unauditable freight-forwarding
  platforms

## Offer
- customs-compliance scope and multi-carrier routing management
- robotics-assisted customs-document verification and cross-dock
  staging/consolidation
- consignment booking, routing and reconciliation records
- shipper billing and disclosure records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per forwarding office/customs desk
- support retainer with SLA
- document-scanning/cross-dock robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (a customs declaration outside verified
  compliance scope, a consolidated shipment dispatched without a
  completed document-verification pass, a reconciliation record
  without verified evidence) require human sign-off
- customs declarations cannot be released outside verified compliance
  scope
- reconciliation records require verified evidence
- sensitive shipper and consignment data stays outside Git
