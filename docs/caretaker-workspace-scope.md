# Caretaker and property operations workspace

This implements blueprint section 13 in the current Flutter app, in three coordinated frontend/backend PR pairs.

1. Property-scoped, password-confirmed caretaker invitations and acceptance; capability/expiry/revocation controls; daily property/unit/common-area tasks; private photo evidence; completion submission, manager review/rework; discussions, notifications and audit history.
2. Minimal operational occupancy context, delegated preparation of existing move-in/out inspections, exact-report tenant review, operational notices and tenant replies. Existing owners retain inspection-policy, lease activation/completion, financial and termination decisions. Notices coordinate work and do not terminate tenancies.
3. Delegated occupied-tenancy maintenance reporting/triage/coordination over existing workflows, contextual tenant/provider communication and caretaker/manager operational reporting. Spending approval and tenant entry/completion consent stay with the existing authorized parties.

General property tasks also cover vacant units/common areas without creating a fictitious tenant or lease. They track operations/evidence; financial procurement for non-tenancy work is a separate extension. This feature does not add payment execution, legal eviction notices or a new frontend framework.

Caretaker permissions are explicit property capabilities within existing organization membership; they do not grant OWNER/ADMIN status. Revocation, removal, expiry and lost agency mandates are rechecked on every read/write and after property-lock waits. Completed evidence/history is retained for current authorized property managers; former caretakers lose operational access.

Business members now need an accepted property capability (or an existing agency agent property assignment) to read inventory. OWNER/ADMIN access is unchanged. Existing unassigned MEMBER accounts must be invited to the relevant properties; this deliberately removes the old landlord-wide member read access.
