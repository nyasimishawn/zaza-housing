# Backend conventions

## Package structure

Use the shared layers under `src/main/java/com/zaza/housing` for all additions:

- `controller`: HTTP mappings, request validation, and delegation to services.
- `service`: business rules, authorization, and transaction boundaries.
- `repository`: database queries, persistence, and row mapping; keep SQL here.
- `model`: domain/account/organization/property/unit/listing records.
- `dto`: request and response types, with one public type per file.
- `enums`: shared domain enums.
- `config`: Spring/security configuration.
- `filter`: servlet/security/rate-limit filters.
- `security`: shared security response helpers.
- `exception`: application errors and API exception handlers.
- `util`: small reusable helpers such as pagination, email normalization, and token hashing.

Extend the relevant existing class when adding behavior. New classes, when needed, belong in the appropriate shared layer. Do not introduce top-level feature packages such as `listing`, `property`, `organization`, or `auth` that mix controllers, services, repositories, and models. Keep request/response declarations out of controllers and services. Controllers call services rather than repositories. Avoid combining unrelated domains into one giant controller or service.

## Git and validation

Use a separate branch for each change: `feature/<name>` or `refactor/<name>`. Do not include `codex` in branch names.

Preserve API paths, JSON contracts, database migrations, and authorization behavior during structural refactors. Run the existing PostgreSQL integration suite with `.\scripts\test-foundation.ps1 -UseLocalDatabase` after changes to application wiring or API behavior. Tests use `zaza_foundation_test`; never direct them at production.

Keep `.env`, tokens, passwords, and generated logs out of commits. Local branch commits do not authorize pushing, deployment, or changes to external services.
