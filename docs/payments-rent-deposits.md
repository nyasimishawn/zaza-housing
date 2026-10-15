# Payments, rent and deposits

This backend slice connects signed leases to billing, verified external payments, rent balances and move-out deposit reconciliation. Source follows the shared controller, service, repository, model and DTO layers. V8 adds the ledger and notification source; V9 protects billing source terms and finalized state.

## Collection and authority

Supported external channels are BANK_TRANSFER, MOBILE_MONEY, CASH and CARD_TERMINAL. These describe money checked outside Zaza. The backend does not call M-PESA, a bank, a card gateway or a refund provider. There is no public callback that can mark money successful. A future gateway adapter must validate provider evidence and feed the internal ledger transactionally. Do not treat a tenant report, a frontend success screen or a provider reference alone as proof of settlement.

Tenant users can review their own shared lease finances, report a payment, download receipts and accept or dispute deposit proposals. Current owner/agency organization OWNER/ADMIN members can read property finances. Financial mutations require a current OWNER/ADMIN membership in the property's owner organization, and the actor must differ from the tenant. Agency management delegation grants financial visibility, not authority to verify receipts, spend deposits or record refunds. Ordinary members, unrelated users and platform administrators without these memberships cannot bypass access checks. Revoked memberships/delegations take effect on the next request.

Receipt recording, report verification/rejection, receipt voiding and refund recording require fresh account password confirmation and share authentication throttling. Passwords are excluded from audit messages, operation fingerprints and receipt storage. Reporting money never changes received funds or invoice balances. Only an owner administrator's independent verification creates a receipt. Manually verified receipts are labeled MANUALLY_VERIFIED_EXTERNAL_PAYMENT.

## Rent policy and opening billing

GET /billing/preview is read-only. POST /billing explicitly selects CALENDAR_ACTUAL_DAYS using the current lease version. The source must be SIGNED, ACTIVE, COMPLETED or TERMINATED, with the unit still held. Billing opens once per lease, freezes the signed revision/currency and creates rent invoices for its complete effective term plus a deposit invoice when nonzero.

The start date is included; the end date is excluded. Calendar-month rent equals monthly rent multiplied by occupied days in that month divided by the actual number of days in that month, rounded HALF_UP to two decimals per invoice. Leap years are included. Due date is the contractual due day in the invoice's month, clamped forward to the period start if necessary. Deposit is due at lease start. The preview shows every period and amount before the owner chooses this policy. Only ISO currencies with two fractional digits and terms spanning at most 600 calendar months are supported; unsupported currencies/longer terms receive 400. Zero-valued rent periods and deposits produce no invoice.

For example, KES 25,000 monthly from October 1 through October 11, end excluded, accrues 10/31 × 25,000 = KES 8,064.52. Calendar dates use the application's UTC clock. No taxes, late interest, automatic fees, exchange conversions or alternate proration policies are applied.

All term invoices are issued up front. `outstanding` includes future invoices; `dueNow` includes unpaid invoices due today or earlier; `overdue` includes those due before today. Invoice states are OPEN, PARTIAL, OVERDUE, PAID or CREDITED. Overdue status is calculated at read time. Additional CHARGE invoices require an amount, due date and reason. Credit notes append history and may reduce only unpaid rent/charge balances. Reverse payment allocations before crediting paid amounts. Deposit obligations are released through finalization rather than ordinary credit notes.

## Payment reporting, verification and allocations

1. Tenant POST /claims with amount, channel, external reference, receivedOn and reason. The report remains PENDING and creates no funds.
2. Owner checks the actual external statement and POSTs /claims/{claimId}/verify with password and reason, or /reject. A report resolves once and stays in history.
3. An owner can also POST /payments directly for an independently checked payment using the same fields plus password.
4. Verified funds initially remain `unallocated`. POST /payments/{paymentId}/allocations to assign an amount to an invoice in the same lease/currency. Partial payments and multiple invoices/receipts are supported. The backend rejects over-allocation against either side.

References are normalized to uppercase and unique per receiving owner organization and channel across leases, receipts and recorded refunds. Voiding a receipt does not release its reference. A repeated reference cannot settle two claims. Received/refund dates must be from 1900 through today; a payment refund cannot predate the original receipt.

Allocation reversal appends a reversal without deleting the original row. A void appends evidence and removes an unused receipt from received funds; it requires zero active allocations and zero refunds. Record a refund of unallocated funds only after the transfer has actually happened and has been independently checked. Refunds cannot exceed available verified funds. Receipt voids, credit notes and refunds are separate operations; none initiates an external transfer.

Per-lease statements distinguish received funds, allocations, refunded amounts, unallocated funds, rentCollected, depositHeld and depositDeducted. Deposit collection does not increase rentCollected. JSON receipt views include allocation/refund history; UTF-8 text downloads identify the original received amount, channel, reference, verifying account, recording time and current VOID/manually-verified status. They contain no card details, bank credentials or passwords. No PDF/tax-invoice format is supplied.

## Move-in and move-out

For a configured account, activation requires fully allocated deposit and rent due through today. Future rent does not block move-in. Billing version changes do not change lease document/lifecycle versions.

Lease completion still occurs on/after natural or mutually accepted early end and moves inventory to UNAVAILABLE for checkout. It may occur with unpaid balances so debt can still be reconciled. Then:

1. Add inspection charges with reasons before finalization.
2. POST /finalize after the lease is COMPLETED/TERMINATED. This credits rent after the effective end date using the selected policy. If that rent was paid, immutable allocation reversals release the excess into unallocated funds; any still-earned remainder receives a new allocation. Unpaid deposit obligation is credited away. Existing paid deposit remains held.
3. Finalization freezes new charges and deposit allocation/reversal. Rent/charge payments, corrections and credit refunds can still clear existing balances.
4. Owner POST /deposit/proposals with deduction and a reason explaining the inspection/deductions. Held amount is frozen in the proposal, and deduction cannot exceed it. The proposal shows held, deduction and refundDue. It does not spend the deposit.
5. Tenant POST /deposit/proposals/{settlementId}/decision with ACCEPT or DISPUTE. Only ACCEPT recognizes the deduction. A disputed proposal remains immutable and may be replaced by a new proposal requiring fresh tenant acceptance. No owner force-accept endpoint exists. An owner may withdraw a PENDING proposal; accepted decisions cannot be rewritten. Reasons and proposal UUIDs provide a basic dispute record; a staffed case-management/arbitration workflow is not implemented.
6. After acceptance, owner records actual deposit refunds through /deposit/refunds. Partial refunds are allowed up to remaining held funds. Refunds/deductions reduce held deposit, and never rent income.
7. Resolve every pending payment report, clear invoice balances, allocate/refund unallocated funds and clear held deposit. Only then is checkoutReady true and lease release-unit permitted. Vacancy confirmation remains separately required.

If held deposit is zero, no deposit proposal is necessary. Deposit deductions are an agreed aggregate with an inspection explanation; this slice does not automatically apply deposits against rent arrears. Clear rent/charge debt separately. Fully deducted deposits need no cash refund. A lease can remain closed with outstanding debt but its unit stays unavailable until financial and vacancy clearance.

Existing leases without billing retain their prior activation/checkout behavior for migration compatibility. Open billing before activation/financial reconciliation to enable these gates. Opening after unit release is prohibited. New tenant reports and receipts are also blocked after unit release; this slice does not reopen a completed financial checkout.

## Concurrency, retries and evidence

Every mutation has a client-generated UUID requestId. Commands after opening use the current billing account `version`; opening uses `leaseVersion`. Reuse the same request ID and payload when retrying a lost response. Replays return the current account summary without duplicate writes/events. Keys are scoped to the lease and bind the actor, action, target resource and canonical request payload. Reusing a key for another payload/action/actor returns 409. Password is revalidated where required but excluded from the stored digest. Fresh stale commands return 409.

Writes reuse property locks shared by application and lease transitions, then recheck current authorization and account version. Concurrent writers cannot overspend funds or accept two competing proposals. Database uniqueness also rejects duplicate external references across properties. Invoice/credit, report/decision, receipt/void, allocation/reversal, refund, deposit proposal/decision, event and operation records are append-only, including truncate protection. Audits and in-app notifications commit with money changes. Failed evidence writes roll the transaction back, including reference reservations and request IDs. A database owner can bypass protections; production runtime privileges must be restricted separately from migration credentials.

Private billing controllers and existing lease controllers return Cache-Control: no-store. Historical inbox messages remain personal records; following a URL still requires current access.

## API reference

Owner base M = /api/v1/organizations/{organizationId}/properties/{propertyId}/leases/{id}/billing
Tenant base T = /api/v1/me/leases/{id}/billing
Both require bearer tokens.

Both support GET base, /preview, /invoices, /payments, /claims, /allocations, /refunds, /settlements, /history, /payments/{paymentId}/receipt and /payments/{paymentId}/receipt/document. Lists use limit 1–100, default 50, and nonnegative offset. Full receipt evidence is scoped by its lease and payment ID.

Owner POST commands:

| Path below M | Request fields in addition to requestId/version |
| --- | --- |
| empty (open) | leaseVersion instead of version; policy |
| /charges | amount, dueDate, reason |
| /invoices/{invoiceId}/credits | amount, reason |
| /payments | amount, channel, reference, receivedOn, reason, password |
| /claims/{claimId}/verify or /reject | reason, password |
| /payments/{paymentId}/allocations | invoiceId, amount |
| /allocations/{allocationId}/reverse | reason |
| /payments/{paymentId}/void | reason, password |
| /payments/{paymentId}/refunds | amount, channel, reference, receivedOn, reason, password |
| /finalize | reason |
| /deposit/proposals | deduction, reason |
| /deposit/proposals/{settlementId}/cancel | reason |
| /deposit/refunds | amount, channel, reference, receivedOn, reason, password |

Tenant POST /claims uses amount/channel/reference/receivedOn/reason. Tenant POST /deposit/proposals/{settlementId}/decision uses decision/reason. Both also take requestId/version. All successful commands return 200 with the current BillingAccountResponse. Monetary fields accept positive amounts with at most 12 integer and two fractional digits; deposit deduction may be zero. Unknown channels/policies, malformed inputs and unsupported currency policies return 400; missing private resources 404, insufficient current role 403, wrong password 401, version/state/balance/reference conflicts 409, password quota exceeded 429.

GET /api/v1/me/leases/billing-summary aggregates only the authenticated tenant's configured accounts. GET /api/v1/organizations/{organizationId}/properties/{propertyId}/leases/billing-summary aggregates the currently authorized property's accounts. Both return one row per currency; currencies are never added together. Reports include outstanding/dueNow/overdue, rentCollected, held deposit, received/refunded and unallocated totals. Private routes only; no public financial dashboard.

Runnable request templates are in api/payments-rent-deposits.http. Always reload version after each different command; examples use placeholders to prevent accidental reuse of stale values.

## Verification and next integrations

Run .\scripts\test-foundation.ps1 -UseLocalDatabase. HTTP integration tests use the isolated zaza_foundation_test schema. Coverage includes authorization/revocation, claim verification, references, precision, stale/idempotent/concurrent commands, immutable evidence, allocations, reversals, refunds, income/deposit separation, early-end credits, disputes, checkout gates, currency-separated dashboards, and audit/notification rollback. Rent calculation tests cover leap years and month boundaries.

Flutter screens/API wiring, online payment initiation and authenticated provider callbacks, actual transfers, M-PESA/bank/card adapters, automated reminders, tax invoices, recurring collection jobs, service/booking payments, commissions/platform fees, owner/provider payouts, chargebacks, formal dispute operations and production accounting exports remain follow-up work. Core lease billing records do not contain gateway-specific identifiers or assumed Kenyan currency/channel rules.


Lease-linked maintenance, private service bookings, provider discovery and work completion are documented in [maintenance-service-requests.md](maintenance-service-requests.md). Started/awaiting-review/disputed maintenance now adds a checkout hold; service jobs do not automatically create rent charges or service payouts.
