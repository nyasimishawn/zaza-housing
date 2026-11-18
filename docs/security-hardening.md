# Security hardening

Direct member provisioning is an explicit owner/administrator action and now requires an active account with a verified email. Unregistered and unverified recipients receive the same failure. Team invitations remain the preferred workflow when recipient acceptance is required. Review existing memberships of unverified accounts separately; this patch does not revoke existing access automatically.

Jackson uses the aligned 3.1.7 BOM, Tomcat 11.0.27 and Guava 33.7.2-jre. The development Keycloak image is pinned to 26.8.2. Back up the persisted Keycloak volume and follow the vendor migration guide before recreating that container. A source pin does not upgrade an already running server. Preserve the existing password-reset restriction until the running server is patched.

The Spring JSON mapper bounds nesting to 64, strings to 65,536 characters, numeric tokens to 128 characters and documents to 1 MiB. Float/double fields accept JSON numbers only. Monetary decimal strings remain supported. Local password login tracks failed password attempts by normalized email and peer in a ten-minute window. Successful password checks preserve the existing failure count. Direct member provisioning uses the per-peer authentication limiter.

Media quotas include all retained images and videos, including archived records. Defaults are 1 GiB/1,000 objects per uploader and 5 GiB/5,000 objects per owning organization, with daily limits of 100 and 500 uploads respectively. `zaza.media.account-bytes` and `zaza.media.organization-bytes` configure the byte limits. Each property retains at most 60 images and 10 videos, with active limits of 30 images and five videos. Video uploads check available capacity before transcoding and recheck the encoded size at commit. Quota checks and media creation share a transaction and account/owner advisory locks, including delegated management paths.

Property managers can read current counts and limits at `GET /api/v1/organizations/{org}/properties/{property}/media-quota`. The response includes the caller's account usage, property media counts, and owner-organization usage when the manager uses the owning organization. Delegated managers see only their account and the property's counts.

Media stores retain 256 MiB of filesystem headroom; video workspaces additionally allow for bounded processing files. Production media should use a dedicated volume with filesystem quotas and monitoring. Do not remove archived files automatically: publication references, audits and retention requirements need an explicit reviewed purge policy. Existing over-quota data will block new uploads; it is not deleted by this migration.

The map cache indexes existing files once, removes stale entries and evicts least recently used tiles within its configured byte budget and 20,000-entry cap. Cached disk reads run outside the shared metadata lock. Four upstream fetches can run concurrently, with up to 16 requests waiting for up to two seconds each. Public tile requests have a separate default of 600 requests per minute per peer (`zaza.discovery.tile-requests-per-minute`). Tile and authentication limits use individual IPv4 addresses and IPv6 /64 prefixes. Give replicas separate cache directories and enforce shared limits at the ingress.

Native forwarding trusts loopback peers by default. For a remote ingress, configure it to replace client-supplied forwarding headers and set `ZAZA_TRUSTED_PROXY_REGEX` to an anchored expression matching only the ingress addresses. Verify client separation through the chosen ingress and tune the per-peer limits with traffic from carrier and office networks that share IPv4 addresses. Enforce request-body size and time limits at the ingress.

Authentication and map request limiters keep at most 10,000 client buckets each. When full, each evicts its least recently used bucket rather than refusing every new client. Fixed windows reset each minute. Under heavy address churn a cold client's bucket can be evicted; use ingress or shared limits for distributed abuse. Invitation creation, resend and cancellation use the real `/api/v1/organizations/{org}/invitations` route and are covered by the authentication limiter.

Public registration returns a generic HTTP 202 acknowledgement. Internal provisioning returns the created account. Coordinate rollout with the Flutter client and review account onboarding and recovery in the deployment readiness checks.

The retained-media migration is V33. Develop's existing V31 and V32 migrations remain unchanged. The security branch includes Develop's structured diagnostics and lease/property changes; do not deploy the older branch base.

Before deployment, verify hosting headers, release signing, provider credentials and cloud rules with the deployment owners. Use separate restricted runtime and migration database roles.

Vendor references:

- https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89
- https://tomcat.apache.org/security-11.html
- https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94
- https://www.keycloak.org/2026/11/keycloak-2682-released
- https://www.keycloak.org/docs/latest/upgrading/index.html
