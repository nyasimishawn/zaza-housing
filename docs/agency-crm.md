# Agency CRM contract

Private routes under `/api/v1/organizations/{agency}/agency` require current AGENCY membership. All responses use `Cache-Control: no-store`. Managers are OWNER/ADMIN; MEMBER agents require both an active property assignment and their lead assignment. Leads without a property can be assigned directly. Agents cannot approve applications, sign on an owner’s behalf or spend funds.

- `GET dashboard`, `properties`, `agents`, `assignments`, `clients`, `leads`, `tasks`: role-scoped pages (`limit`, `offset`). Clients/leads support `q`; leads support `stage`, `mine`; tasks support `lead`, `status`, `due=OVERDUE|UPCOMING`.
- `POST assignments`: property, member, active flag, current version for an existing assignment, reason.
- `POST clients`, `PUT clients/{id}`: consented contact and country/city/currency/budget/bedroom requirements. `POST clients/{id}/registered-customer` binds an authorized enquiry’s customer permanently. `POST clients/{id}/status/ACTIVE|ARCHIVED` archives/restores.
- `GET enquiries`: manager-only authorized listing enquiry choices. `GET clients/{id}/matches`: current public listings matching preferences within accessible properties.
- `POST leads`; `POST leads/{id}/assign|link|stage|note|call|email`. Early-stage property/enquiry links require one matching registered customer. Calls/emails record interactions and never send externally.
- `GET leads/{id}/workflow?kind=VIEWING|APPLICATION|LEASE`: minimal authorized source choices. VIEWING requires confirmed/completed enquiry evidence, APPLICATION requires a submitted current source, WON requires its actual signed lease and source application. Conversion evidence is retained.
- `POST tasks`; `POST tasks/{id}/complete|cancel|reschedule`: assigned, dated in-app follow-ups. No external reminder provider is configured.
- `GET history/CLIENT|LEAD|TASK/{id}`: append-only interactions and command history under current record access.

Every write carries a UUID `requestId`; mutations also carry a current `version`. Identical requests replay without duplicate history. Changed actor/payload/target reuse conflicts. Writes serialize agency membership and property authority before record changes. Removal revokes assignments and clears lead assignment; restoring delegation does not reactivate prior grants.

Migration V17 follows the property ownership verification V16 branch. Merge/deploy that dependency first.
