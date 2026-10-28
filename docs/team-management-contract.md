# Organization team management contract

Flutter uses the existing `/api/v1/organizations/{organizationId}/members` endpoints. No invitation or email delivery is performed: adding a member requires an already registered active account and immediately grants the chosen business membership.

| Action | Contract |
|---|---|
| GET members | Paginated list with limit/offset; current OWNER or ADMIN only. Response fields: userId, email, displayName, role. |
| POST members | `{ "email": "registered@example.test", "role": "MEMBER" }`; 201 on creation. Owner may also grant ADMIN. |
| PATCH members/{userId} | `{ "role": "ADMIN" }` or MEMBER; 200 with the updated member. |
| DELETE members/{userId} | 204 after removal. Subsequent scoped access is denied even with an existing token. |

Only the OWNER can add, modify or remove administrators. An ADMIN can add/manage MEMBER records only, including when the target is their own account: existing ADMIN records cannot be demoted or removed by administrators. OWNER cannot be granted, changed or removed through these routes. Ordinary members cannot list or manage teams. Platform administration is not granted by membership commands.

Invalid input returns 400, denied privileges 403, inaccessible organization/unregistered account 404, duplicate membership 409. Successful changes append audit evidence. Commands have no optimistic version/idempotency contract: do not automatically retry a lost response. Reload authoritative organization and member state, inspect whether the change committed, then request fresh review before another write.

The UI must recheck current authority, show the target identity/role and immediate-access consequences, require confirmation and clear private drafts on session changes. The backend remains authoritative for concurrency and permissions.

`OrganizationTeamManagementTests` checks successful mutations/projections/audit, admin restrictions, ownership protection, existing-token demotion and rejected duplicate/unknown/invalid requests. No application endpoints or migrations are changed.
