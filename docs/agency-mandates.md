# Agency mandates



V18 depends on CRM V17. Owner/admin managers of LANDLORD or AGENCY businesses use `/api/v1/organizations/{org}/agency/mandates`. The owner offers frozen property version/location, responsibilities, fee terms and start/end instants with password-confirmed consent. A separate representative of the selected agency accepts the unchanged offer. A changed property, conflicting management grant or elapsed period blocks acceptance. The recipient can decline; the owner can cancel a pending offer; either party can revoke an accepted mandate.



Reads list only the current business’s offers and agreements. Minimal agency name search and owned active property pickers support creation. Terms and acceptance evidence cannot be rewritten or deleted. Mutations use current versions, UUID idempotency, current membership and append-only activity.



Delegation refers to its source mandate. Every property, listing, screening-document, conversation, viewing and management notification query checks current mandate validity. Future agreements grant no access before their start. Expired agreements grant no access even without a scheduled worker. Explicit manual owner grants clear the mandate pointer, preserving existing manual-delegation behavior. Revoking an older mandate never removes a newer independent grant. Replacing a mandate invalidates earlier agent property grants.



Fee text records an agreement; it does not execute or automatically invoice a payment.

