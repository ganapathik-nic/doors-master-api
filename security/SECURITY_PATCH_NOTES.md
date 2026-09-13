# Security remediation — securityscan

Date: 2026-09-13. Baseline: ba7f779a12d1e8316db06f469c1613d63bd00017.

These are local source replacements, not a deployed security certification. The original audit in output/security/master-api-static-audit-ba7f779.md describes the baseline, not the patched state. No remediation commit, push, or merge has been performed.

## Finding-to-patch map

| Finding | Replacement/control |
| --- | --- |
| F01: proposal overwrite/overposting | TemplateProposalRequest creates fresh entities, rejects supplied IDs, and excludes governance fields. Both proposal controllers validate it; QueryApprovalService resets server-owned fields and verifies linked requests. |
| F02: privileged target role | AdminUserController checks the existing target and requested role. SecurityAdmin targets cannot be changed through this route. SecurityAdmin promotion of standard users remains possible but requires subsequent activation approval. |
| F03: unapproved execution | ReportViewerService requires an active APPROVED template before dispatch, matching supplied identifiers and enforcing active Agent intersection. |
| F04/F12: alternate API bypasses | ExternalConsumerController and PublicReportApiController delegate to the existing governed gateway. ApiClientExecutionPolicy checks client/query entitlement and filter configuration before dispatch. Interceptor coverage includes these aliases. |
| F05: ignored document allowlist | DocumentPolicyAccess requires active policy and explicit authenticated client membership; empty membership denies. Eligibility receives a server-controlled authenticatedClientId. |
| F06: substituted document | DocumentGrantService issues five-minute database grants bound to client/service/template/policy and exact download coordinates from the manifest. Download requires that grant and repeats configured eligibility checks using stored parameters, requiring matching download ID and filename. |
| F07: SQL policy bypass | SqlSecurityValidator parses exactly one SELECT and conservatively rejects prohibited SQL structures/functions. Proposal, approval, report dispatch, and Agent execution share this check. Native repository values remain bound parameters. JSqlParser 4.6 matches this Spring Data runtime. |
| F08: unapproved evidence disclosure | DataRequestAccess allows managers, record owners, and developers reading the existing global approved queue; other developer reads deny. Applied before request/template detail and attachment retrieval. |
| F09: cross-client telemetry | Active client required; update binds both trace ID and client name and requires exactly one affected row. |
| F10: arbitrary manager execution | Stream requests must match approved registered resources; execution controllers and QueryExecutionService use ReportViewerService and authenticated actor identity. |
| F11: disabled TLS verification | Shared WebClient uses normal certificate validation; insecure trust manager and content wiretap removed. |

Additional changes: strict Agent/category create DTOs, report validation, current database role/active-state JWT checks, trusted-proxy boundary, distinct query/contract approval actors, fail-closed response encryption, externalized transit secret, corrected PartialReportResponse package, authenticated auditing, and consolidation of the duplicate DataPullRequest entity into ExternalRequest (legacy requestId JSON alias retained). The removed duplicate entity remains recoverable from Git.

## Deployment prerequisites and intentional behavior changes

1. Configure DOORS_AGENT_TRANSIT_SECRET (or doors.security.agent-transit-secret), at least 32 characters, consistently with the separately deployed Agent. Missing configuration fails encrypted credential dispatch. Do not reuse the previously embedded secret for a new deployment. This patch does not rotate deployed Agents.
2. Use trusted server certificates, including your internal CA in the JVM trust store where needed. Untrusted/self-signed endpoints will no longer be accepted automatically.
3. Set doors.security.trusted-proxies to the actual ingress proxy CIDRs. Default is loopback only. Restrict direct backend access; the trusted proxy must replace control/host headers, not blindly preserve caller values. Untrusted peers cannot supply forwarding or manager-plane headers.
4. Review and apply both Flyway migrations through your deployment procedure. Grants use shared PostgreSQL state, supporting multiple application replicas. A manifest must be refreshed within five minutes before a download. Configured policy membership must include the client explicitly; approved eligibility results must return the same download_id and file_name, and policy document-type mappings must match.
5. Legacy external aliases now use the gateway request and encrypted response contract. Existing clients expecting the old raw response must migrate. Check the UI and SDK end-to-end in staging; no UI application files were changed here.
6. Existing SQL containing prohibited keywords even in literals/comments may be rejected by the conservative SQL policy. Review approved templates before rollout. An SQL parser is not a sandbox: enforce read-only Agent database accounts, restricted functions/extensions, bound parameters, statement timeouts, row limits, and network egress independently.
7. Self-approval is no longer accepted for queries/contracts. Developer access to unapproved requests is limited to the request owner. This preserves the existing global approved developer queue, rather than inventing per-developer assignment.
8. Live application-context and Ollama-dependent tests require DOORS_RUN_INTEGRATION_TESTS=true. Only enable this with explicitly disposable test configuration; ordinary test execution must not start the development application against a real database.

## Verification and operational side effects

Focused security tests cover overposting, supplied IDs, role-target protection and allowed promotion, lifecycle denial before dispatch, aliases, document allowlists/grant coordinates, unsafe and valid SQL, request evidence, arbitrary stream destinations, telemetry ownership, authenticated legacy actors, and real Spring Security HTTP authentication/role/CSRF/validation behavior. Existing tests are retained.

An early application-context test unintentionally connected to the configured development database and applied V2026091301 (document_access_grants table and expiry index). It did not alter existing business rows through that migration. The run was interrupted; do not change that applied migration's checksum. V2026091302 adds eligibility_json and remains a deployment prerequisite. Subsequent application-context execution is opt-in. Existing AIra tests also contacted local Ollama and generated/refreshed the pre-existing untracked knowledge cache; that cache is excluded from the patch.

Verification on Java 21: mvn -q test completed successfully: 101 tests discovered, 91 passed, 10 skipped, zero failures/errors. Skips include explicitly gated external-service/application-context tests and the existing disabled external gateway fixture test. mvn -q -DskipTests package also succeeded. git diff --check passed with repository line-ending settings. No live Agent exploit, production database test, browser/SDK compatibility test, dependency vulnerability scan, or production ingress test was performed.

## Residual architectural work / release gates

- Human usernames and API-client names still share the existing name-based Agent mapping schema. Exact equality replaces wildcard/inconsistent matching, but a typed-principal migration and collision audit are still required. Do not approve release with colliding identities.
- A manifest grant proves that the configured manifest returned that record for this client; it cannot prove business-row ownership inside arbitrary stored SQL. Review manifest/eligibility SQL and the separate Agent implementation against the intended tenant/record policy.
- Existing dynamic Map-based endpoints retain their manual validation. All five original direct JPA entity-binding sites are replaced, but this is not a claim that every request DTO now has comprehensive field constraints.
- Legacy ApiResponse.error(message, code) still relies on its controller to set HTTP status. A repository-wide response-contract migration is not included.
- The unused AgentClient class is retained for source compatibility, but production QueryExecutionService no longer calls its old protocol. Confirm the current wire contract against the deployed Agent.
- Isolation/locking, rate/resource limits, secret rotation, and downstream database privilege enforcement require deployment-level validation. Passing static/regression checks does not close these release gates.

## Applying the standalone patch

Use a clean checkout of the baseline commit and first run git apply --check with output/security/master-api-securityscan.patch; then apply it and run the Java 21 tests. The working repository already contains these replacements: do not apply the patch a second time here.

Merge advice: use reviewed PRs into main after these release gates and staging checks pass. Merge by verified content and ancestry, not merely whichever branch is newest. Reconcile other outstanding branches explicitly; UI and backend should have separate coordinated PRs.
