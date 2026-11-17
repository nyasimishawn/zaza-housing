# Security remediation — 16 November 2026

Direct member provisioning is an explicit owner/administrator action and now requires an active account with a verified email. Unregistered and unverified recipients receive the same failure. Team invitations remain the preferred workflow when recipient acceptance is required. Review existing memberships of unverified accounts separately; this patch does not revoke existing access automatically.

Jackson uses the aligned 3.1.7 BOM, Tomcat 11.0.27 and Guava 33.7.2-jre. The development Keycloak image is pinned to 26.8.2. Back up the persisted Keycloak volume and follow the vendor migration guide before recreating that container. A source pin does not upgrade an already running server. Preserve the existing password-reset restriction until the running server is patched.

The Spring JSON mapper bounds nesting to 64, strings to 65,536 characters, numeric tokens to 128 characters and documents to 1 MiB. Float/double fields accept JSON numbers only; monetary decimal strings remain supported. Local password login has a persistent normalized-email quota of 20 attempts per ten minutes. Direct member provisioning also uses the existing per-peer authentication limiter.

Media quotas include all retained images and videos, including archived records. Defaults are 1 GiB/1,000 objects per uploader and 5 GiB/5,000 objects per owning organization; daily upload limits are 100 and 500 respectively. `zaza.media.account-bytes` and `zaza.media.organization-bytes` configure the byte limits. Each property retains at most 60 images and 10 videos, while the existing 30-active-image and five-active-video limits remain. Quota checks and media creation share a transaction and account/owner advisory locks, including delegated management paths. Existing files remain available. Restoring an archive does not consume another retained slot.

Media stores retain 256 MiB of filesystem headroom; video workspaces additionally allow for bounded processing files. Production media should use a dedicated volume with filesystem quotas and monitoring. Do not remove archived files automatically: publication references, audits and retention requirements need an explicit reviewed purge policy. Existing over-quota data will block new uploads; it is not deleted by this migration.

The map cache uses an in-memory access-order index, evicts least recently used tiles within its configured byte budget, and caps entries at 20,000. It indexes existing files once, removes stale files and empty coordinate directories, and permits four simultaneous upstream fetches without an additional fetch wait queue. Public tile requests have a separate 240-request/minute per-peer limit (`zaza.discovery.tile-requests-per-minute`); authentication limits are independent. Use one writer per tile-cache directory; replicas should use separate cache directories. Cross-replica limits require an ingress/shared rate limiter.

Forwarded headers are ignored by default. For an ingress that replaces client-supplied forwarding headers, set `ZAZA_FORWARD_HEADERS_STRATEGY=native` and `ZAZA_TRUSTED_PROXY_REGEX` to an anchored regex matching only that ingress's peer addresses. The default trusted pattern covers loopback only. Do not use `.*` or enable framework forwarding that trusts arbitrary peers. Check two distinct client IPs through the actual ingress and confirm independent buckets before exposure. The ingress must also enforce request-body size/time limits.

Deployment work remains: verify actual hosting headers, protect and supply Android release-signing credentials, check live Firebase rules and API-key restrictions, review historical Google Maps key exposure and rotate/restrict any affected key, and use separate restricted runtime and migration database roles. No cloud credentials, stored accounts, database grants, live services or production data are changed by the source patch.

Vendor references:

- https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89
- https://tomcat.apache.org/security-11.html
- https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94
- https://www.keycloak.org/2026/11/keycloak-2682-released
- https://www.keycloak.org/docs/latest/upgrading/index.html
