# Service payments and provider payouts

This is the backend workflow for privately booked services and owner-funded maintenance. It records independently checked external collections, refunds and provider transfers. `collectionMode` is `MANUAL_EXTERNAL_VERIFICATION`: this API does not initiate transfers, charge cards, call M-PESA, hold a regulated escrow account, verify bank ownership automatically, or accept frontend success as proof of payment. Only record funds actually received by the platform or its authorized collection operator. A payment directly to a landlord or provider is not platform funding. Rent/deposit receipts cannot fund a service payout.

## Account and payment lifecycle

1. Complete the existing provider assignment and quote approval. A maintenance owner administrator pays; an agency can coordinate work but cannot authorize owner funds. The tenant pays for a private service booking.
2. POST `{workPath}/payments` with a fresh `requestId` and current `workVersion`. Use the tenant work path for personal bookings and the owner's property work path for maintenance. This creates an account for the exact accepted quote and assignment, freezing gross amount, currency, payer, provider and commission. A quote has only one account, including after closure. A new account requires a newly agreed quote.
3. The payer can report an external transfer using POST `{accountPath}/claims`. This creates an unconfirmed claim and does not change collected funds. A platform operator independently checks receiving statements and password-confirms verification or rejection. An operator can also record a receipt directly after checking external records.
4. Partial payments and overpayments are recorded. `due` is the positive difference between quote and verified net collections. Work may complete while payment is outstanding; completion does not mark an account paid.
5. Before provider settlement, operators may record externally completed partial/full refunds against a particular receipt. Only uncommitted funds can be refunded. Voiding a receipt is a correction, never a refund or money transfer. Voided references remain reserved.
6. Work must be `COMPLETED`, the current quote/assignment must still match, net collections must cover the full quote, and the clock must be strictly beyond the 14-day completion dispute period before requesting a payout. At the exact 14-day boundary, the customer can still dispute and payout remains blocked.
7. The provider requests its full frozen entitlement to an independently verified destination. This reserves principal and commission. A platform operator approves or rejects. A second independent operator password-confirms the externally completed transfer, with exact amount, channel, unique reference and date. Only then do `paid` and `feesEarned` increase.

The service statement exposes `received`, `refunded`, `netCollected`, `due`, `providerEntitlement`, `reserved`, `paid`, `feesEarned`, `refundAvailable`, `payoutAvailable`, `eligibleAfter` and a specific `payoutBlocker`. `status` describes account openness; use paid/reserved/blocker to determine settlement progress. Opening advances the financial account version to 1. Its version is independent of the work request version.

`ZAZA_SERVICE_COMMISSION_BPS` defaults to **0**, must be 0–5000, and is frozen per account. 250 basis points means 2.5%. The commission is included in the accepted quote, rounded half-up to two decimals; it is deducted from the provider entitlement rather than added to the payer's bill. A gross 1,500.00 quote at 250 basis points gives a 37.50 fee and 1,462.50 provider entitlement. Changing configuration does not reprice an existing account. Set the agreed commercial policy before opening accounts; the API does not imply tax calculations or tax-invoice compliance.

## Private API paths

All paths require bearer authentication. Lists use `limit` 1–100 (default 50) and nonnegative `offset`. Every financial response sends `Cache-Control: no-store`.

| Scope | Path |
| --- | --- |
| Personal service accounts | `/api/v1/me/service-payments` and `/summary` |
| Owner-funded maintenance accounts | `/api/v1/organizations/{ownerOrg}/service-payments` and `/summary` |
| Provider financial accounts | `/api/v1/organizations/{providerOrg}/provider-payouts/accounts` |
| Provider currency totals | `/api/v1/organizations/{providerOrg}/provider-payouts/summary` |
| Provider payout queue | `/api/v1/organizations/{providerOrg}/provider-payouts/requests?status=REQUESTED` |
| Provider beneficiary records | GET/POST `/api/v1/organizations/{providerOrg}/provider-payouts/destinations` |
| Shared account statement | `/api/v1/service-payments/{accountId}` |
| Account safe event history | `{accountPath}/history` |
| Incoming claims/receipts/refunds | GET `{accountPath}/claims`, `/receipts`, `/refunds` (payer/operators only) |
| Report incoming payment | POST `{accountPath}/claims` |
| Payout list/request | GET/POST `{accountPath}/payouts` |
| Provider withdraws unapproved request | POST `{accountPath}/payouts/{payoutId}/cancel` |
| Payer closes refunded account | POST `{accountPath}/close` |
| Operator account queue/totals | `/api/v1/admin/service-finance/accounts` and `/summary` |
| Operator payout queue | `/api/v1/admin/service-finance/payouts?status=APPROVED` |
| Operator provider destinations | `/api/v1/admin/service-finance/providers/{providerId}/destinations` |
| Operator verifies/revokes beneficiary | POST `/api/v1/admin/service-finance/destinations/{destinationId}/review` |
| Operator records checked receipt | POST `{adminAccountPath}/receipts` |
| Operator verifies/rejects claim | POST `{adminAccountPath}/claims/{claimId}/verify` or `/reject` |
| Operator voids receipt | POST `{adminAccountPath}/receipts/{receiptId}/void` |
| Operator records external refund | POST `{adminAccountPath}/receipts/{receiptId}/refunds` |
| Operator payout decisions | POST `{adminAccountPath}/payouts/{payoutId}/approve`, `/reject`, `/cancel`, `/fail`, `/paid` |

`workPath`: `/api/v1/me/work-requests/{workId}` for private bookings, or `/api/v1/organizations/{ownerOrg}/properties/{propertyId}/work-requests/{workId}` for maintenance. `accountPath`: `/api/v1/service-payments/{accountId}`. `adminAccountPath`: `/api/v1/admin/service-finance/accounts/{accountId}`.

Queue statuses are `REQUESTED`, `APPROVED`, `REJECTED`, `CANCELLED`, `FAILED`, `PAID`. Reject applies before approval; operator cancel/fail applies after approval and confirms no successful transfer was made. Provider cancellation is allowed only while requested. Rejected/failed/cancelled requests remain in history; the provider explicitly creates a new request with a fresh key to retry. Never mark an uncertain bank result failed and resend: reconcile it externally first. Partial payouts, payout batches, chargebacks, transfers with uncertain outcomes, fee reversals, tax withholding and post-settlement recovery need additional workflows before live gateway integration.

## Beneficiary records and financial authority

Beneficiaries are immutable versions with an external opaque `beneficiaryToken`, masked label, channel (`BANK_TRANSFER`/`MOBILE_MONEY`) and two-decimal currency. The token is a non-secret identifier already maintained by the external bank/payment system; do not enter account numbers, card details, passwords or API keys. Labels have a name followed by `****` and two to four trailing digits, for example `Business Bank ****1234`. A newly registered record is `PENDING`. An independent platform operator verifies identity and ownership outside this app, then records `VERIFIED` with password confirmation and a reason. `REVOKED` immediately prevents approval and final payout recording, including for existing requests. Corrections use a new destination; requests retain the original immutable destination ID. Verification is operational evidence and does not change a public provider profile's `NOT_VERIFIED` label.

`PLATFORM_ADMIN` explicitly grants the service-finance operator routes and service-ledger reads. It does not grant private work/property records outside existing membership rules. There is no public role-grant endpoint or automatically created operator. Provision verified operators through the existing controlled, audited administration procedure. A finance operator cannot verify transactions for their own customer account, a maintenance payer organization they belong to, a provider organization they belong to, or work they occupy. This includes ordinary members. Payout approver and final-transfer verifier must be different accounts.

Current provider OWNER/ADMIN users can read their organization's frozen financial accounts and payout outcomes. Incoming bank/mobile references, claim descriptions and refund evidence are restricted to payer/operators. Historical financial rights do not restore site addresses, tenant identities or access to a released/reassigned work request. Maintenance occupants and delegated agencies receive no service financial statement or financial inbox messages. Personal booking statements are not disclosed to landlords.

## Versions, retries and accounting rules

All mutations use a UUID `requestId`; account mutations additionally require its current `version`. An exact authorized retry returns the current resource without another receipt/reservation/refund/transfer. Reusing a key for another actor, action, target or payload returns 409. Passwords are recursively excluded from stored fingerprints, but operator retries still require the correct current password. Quote opening and destination registration/review also have scoped idempotency. Financial password confirmations share authentication throttling.

Money uses decimal two-place values; positive payment/refund amounts allow at most 12 integer digits. References allow letters/digits and `._:/-`, normalize to uppercase, and are unique across all recorded service collections, refunds and payouts for each channel. They remain reserved after voids. Different rent/service collection authorities have separate registries. Dates cannot be in the future; refunds cannot precede their source receipt, and payout dates cannot precede release eligibility or verified funding.

`netCollected = received - refunded`. A request reserves `gross - fee`, plus the fee in refund availability. Paid funds and earned commission cannot be refunded from the same principal. Excess verified collections can still be refunded after settlement. Commission is recognized only when the provider transfer is recorded as paid. Dashboard totals group each currency separately; quoted totals include historical closed accounts and must not be treated as current outstanding receivables.

An open service account blocks replacement quotes, provider release/decline/reassignment and work cancellation. Resolve all pending payment reports, cancel any pending payout, refund all uncommitted collections, then let the payer close the account before changing procurement. Closed accounts retain all evidence and reject new money. A settled paid account cannot be closed as if it were refunded. Work disputes freeze eligibility, and rework requires fresh completion approval and a new full dispute period. Financial actions never automatically charge a lease, consume a deposit, fabricate service completion or send money.

All writes serialize with the existing property/work lock, following owner-organization → property → provider-organization order. Work and financial versions are separate. Concurrent stale mutations lose with 409. Account changes, append-only evidence, idempotency, audit and targeted inbox events commit together. V11 creates the ledger and inbox extension; V12 protects quote-source identity, immutable history/truncation and adds history indexes. Migrations V1–V10 are unchanged. Runtime database privileges still need to be restricted for production; database owners can bypass trigger protections.

## Run and verify

Use the existing `.env` and `scripts/start-local.ps1`. Configure the commission via an environment variable before startup if an agreed fee is required; default is zero. No new credentials or dependencies are required. `api/service-payments-provider-payouts.http` includes manual requests with placeholder bearer tokens, account versions and external evidence. They do not run automatically.

Run `scripts/test-foundation.ps1 -UseLocalDatabase` with Java 24 to exercise real HTTP/PostgreSQL integration and build the executable jar. Test JDBC and Flyway remain fixed to the isolated `zaza_foundation_test` schema. Never run these tests against production. Test receipts and beneficiary identifiers are generated examples; no actual bank, mobile-money or card transfers occur.

Flutter screens, live collection/payment instructions, external account provisioning, gateway adapters and signed webhooks, asynchronous reconciliation, true provider/KYC checks, operator permission granularity and live transfer execution remain separate work.
