# Workspace membership contract

One authenticated account can belong to multiple LANDLORD, AGENCY and SERVICE_PROVIDER organizations with different OWNER, ADMIN or MEMBER roles. A Flutter workspace choice is navigation state, not a new login, token or authorization grant. Personal tenancy activity continues under the same account.

`GET /api/v1/organizations?limit=20&offset=0` lists only the caller's current memberships. Follow pages until a short page; `GET /api/v1/organizations/{id}` supplies fresh organization name/type/role. Recheck before displaying choices and before privileged operations. Revocation removes the organization from subsequent reads with the existing token; demotion changes its role without revoking personal sign-in. Unrelated organizations return 404.

Switching requires no backend write and must not mutate platform roles. MEMBER membership grants neither manager access nor provider-job commands. Property access additionally depends on owner/delegation scope. The frontend must clear late responses on account changes and replace scoped views on selection changes. Failed refresh must not be interpreted as a successful empty membership list.

`WorkspaceMembershipTests` covers one account's mixed organization types/roles, bounded complete pagination, unrelated organization privacy, role updates and revocation with an already-issued token. Existing application APIs, migrations and authorization behavior are unchanged.
