# Governance

`cloud-itonami-5229` is an OSS open-business blueprint for community
freight forwarding and customs brokerage, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Freight Forwarding Governor remains independent of the advisor.
- hard policy violations (an out-of-scope customs declaration, an
  unverified consolidated-shipment dispatch, an unverified
  reconciliation record) cannot be overridden by human approval.
- every dispatch, sign-off, declaration and reconciliation path is
  auditable.
- sensitive shipper and consignment data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or compliance-scope checks
- mishandling shipper or consignment data
- misrepresenting certification status
- failing to respond to safety incidents
