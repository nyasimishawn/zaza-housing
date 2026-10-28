# Agency commissions and reporting

V19 follows CRM V17 and mandates V18. `POST /api/v1/organizations/{agency}/agency/commissions` records an agreed exact amount and signed-lease currency for the immutable converting agent of a WON lead. The deal must have its real customer/property application and signed lease. One record per agency/lead/agent prevents duplicate claims. An ordinary agent can submit only their own accessible deal; managers can submit for the converting agent.

`POST commissions/{id}/approve|reject|void|pay` uses current versions, UUID replay, password-confirmed consent and retained history. A different manager must approve and record payment of a recipient’s commission. A completed full payment requires its external reference and actual payment instant after approval and no later than now. References cannot be reused within the agency. Paid rows, frozen amounts/agreements and approval evidence are immutable. This is a ledger of completed transfers, with no bank or mobile-money execution.

`GET commissions`, `commissions/{id}`, and `commissions/{id}/history` give managers agency-wide access and current member agents their own retained financial history, even if property assignment later ends.

`GET reports?from=YYYY-MM-DD&to=YYYY-MM-DD&limit=20&offset=0` covers at most 367 inclusive UTC dates. Current pipeline/overdue work uses live record access. Lead creation, signed conversions and completed tasks use their respective event dates. Immutable conversion attribution survives reassignment and membership removal. Agent performance is paginated; members receive only their own figures. Monetary totals group by currency: eligible commission creation, approval, and completed payment each use their corresponding dates. There is no cross-currency sum or inferred exchange rate.

Integration finishing also adds names to property assignment projections, checks active agent accounts, paginates workflow kind pickers correctly, and includes active assigned agents in enquiry/viewing notifications.
