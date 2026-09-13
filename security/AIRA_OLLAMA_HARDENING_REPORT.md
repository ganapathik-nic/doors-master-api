# AIra / Ollama orchestration hardening

Date: 2026-09-13. Repository: masterapi. Branch: securityscan.

## Decision

The service-layer patch now enforces destination and HTTP-role boundaries, minimizes data sent to Qwen, and adds prompt/response handling safeguards. It does **not** make Qwen immune to semantic prompt injection. An air gap restricts connectivity; it does not prevent poisoned local documents or misleading model output.

Scope was the Java services that construct Ollama traffic: AiraService, AiraAgent, AiraKnowledgeService, and their AiraContextService/AiraOperationalAnswerService data sources. AiraProperties and the application YAML were inspected only to establish configuration provenance. Controllers, UI rendering, infrastructure, and the deployed VM were not changed or tested. Existing unrelated security patches were preserved.

## Actual data flow and existing protections

- Documentation questions: user text -> embedding request -> local vector lookup -> reference chunks -> Qwen chat -> answer. Sources are packaged Markdown/PDF documents and the persisted local vector cache, not arbitrary PostgreSQL query results.
- Operational questions: Java intent classification -> role-checked aggregate PostgreSQL metrics -> deterministic Java answer. This path does not require Qwen.
- Mixed questions previously appended the deterministic metrics answer to the model's reference prompt. The patch returns those metrics separately and prefixes the Java-generated answer after model generation; database content is not sent to Qwen on this path.
- AiraContextService.buildOperationalContext formats registry descriptions and names from PostgreSQL, but source search found no active production caller passing that method to Qwen. Its weak newline-only cleaning was a latent risk, not a demonstrated current indirect-injection route.
- The original AiraAgent already had a hardcoded system-role instruction that reference text is data. No tools, automatic SQL execution, URL-fetching tool, or shared chat memory were registered. The audit does not characterize the original pipeline as an autonomous database agent.

## Findings and implemented remedies

| ID | Assessment | Evidence before patch | Remedy |
| --- | --- | --- | --- |
| AI-01 | Medium: incomplete instruction/data separation | AiraService.chat assembled reference delimiters, retrieved chunks, the question and trusted response guidance in one user string. Only the question was HTML-escaped; reference delimiters could be imitated inside chunks. Existing system-role separation mitigated, but did not resolve, this semantic trust mixing. | AiraPromptBoundary serializes question/references as JSON data. AiraOllamaClient always builds exactly one system and one user message. Hardcoded guidance stays in the system role; user text never becomes a role, JSON key, model option or endpoint. |
| AI-02 | Medium hardening gap; no demonstrated request-driven SSRF | Base URL came only from doors.aira configuration, not request fields. AiraProperties has setters; chat, embedding initialization and health resolved configuration at different times, with no common URI policy. | Final destination/model snapshots at construction; canonical private/loopback IPv4 plus explicit port; reject DNS names, public/link-local addresses, userinfo, paths, query strings, fragments and ambiguous addresses. Closed endpoint enum; redirects and inherited JVM proxies disabled for all three operations. |
| AI-03 | Medium: weak structural filtering / cached-source boundary | User text was trimmed/HTML-escaped; source text and embedding inputs were not consistently checked for role tokens, invisible controls or fences. Persisted cache content could later enter the prompt. | NFKC normalization; removal of format/control characters; rejection of known reserved role/control markers; removal of fenced code, common Markdown formatting, HTML tags and Markdown link/image targets. Suspect retrieved chunks are excluded before generation and extractive fallback. Reindexing quarantines rejected chunks before embedding/storage; cached chunks are checked again at generation. |
| AI-04 | Medium: live metric answer integrity | Mixed requests let Qwen rewrite an authoritative Java-generated metric summary. | Live answers/metrics bypass the model; Java appends the deterministic answer after generation. Model wording is not used to drive operational actions. |
| AI-05 | Low/Medium: boundary robustness and data logging | Separate clients had inconsistent transport behavior; health read a complete string response; semantic-search debug logging included user text. | Shared wrapper implementation, bounded response subscriber, request/answer/vector bounds, timeouts, concurrency gate, no raw upstream error bodies, and no query-text debug log. Reject malformed chat envelopes and model tool calls. |

These are static findings and deterministic boundary tests, not proof of exploitation against the running Qwen model. JSON role separation prevents transport-level role injection but does not confer semantic trust on retrieved text. OWASP likewise treats structured separation as defense in depth and notes that prompt injection has no foolproof general prevention. [OWASP prompt-injection prevention](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html), [OWASP LLM01](https://genai.owasp.org/llmrisk/llm01-prompt-injection/).

## Concrete Java boundaries

Paths below are relative to src/main/java/org/gepnic/doors/masterapi/service.

- AiraOllamaClient.java: immutable outbound origin/model snapshot; destination validation; explicitly non-proxied, non-redirecting HTTP client; only /api/chat, /api/embeddings and /api/tags; structured message construction and response checks.
- AiraPromptBoundary.java: centralized text normalization/filtering, reference quarantine/budget and Jackson JSON envelope. It deliberately does not claim to recognize every natural-language attack.
- AiraService.java: sanitized input before retrieval, trusted guidance separated from data, consistent retry construction, sanitized fallback sources, and live-data bypass.
- AiraKnowledgeService.java: embeddings use the same pinned transport; reindexing stores accepted normalized text only. A failed/empty reindex does not publish an empty replacement store.
- AiraContextService.java: registry text cleaning uses the same control-syntax checks, should the currently unused text helper be used later.
- AiraAgent.java: deprecated legacy interface; production no longer uses its separate LangChain chat adapter.

The chat payload uses Ollama's documented messages/role/content interface with stream=false and fixed generation options. No tools or request-supplied options are emitted. The legacy /api/embeddings endpoint is retained to match the repository's existing LangChain4j-era wire contract; it must be smoke-tested on the actual deployed Ollama version. No model/server upgrade was performed. [Ollama chat API](https://docs.ollama.com/api/chat), [Ollama API reference](https://github.com/ollama/ollama/blob/main/docs/api.md).

## Configuration and compatibility

1. Existing application.yml and application-dev.yml resolve doors.aira.base-url from DOORS_AIRA_BASE_URL, defaulting to http://127.0.0.1:11434. No request parameter or user metadata setter was found for this value. YAML itself is not immutable; the new clients take final validated snapshots and never re-read the URL at request time. Both client instances are Spring-service lifetime objects.
2. Set that deployment property to the approved VM's literal RFC1918 IPv4 and explicit port before startup. This patch deliberately rejects hostnames, IPv6, path-prefixed reverse proxies and unusual numeric address encodings. If your actual configuration uses any of those forms, do not deploy until an explicit alternative pinning policy is designed and tested.
3. HTTP remains allowed for the private air-gapped deployment; that is not transport authentication. Use HTTPS with an appropriate internal CA and IP subject-alternative-name certificate, or a separately approved authenticated tunnel. The VM must firewall ingress to this backend, and backend egress must permit only the exact approved VM/port. Merely belonging to a private subnet does not authenticate a server.
4. Changing URL/model properties on the mutable configuration object after startup no longer changes any outbound destination/model. Restart under controlled deployment to change these settings. The wrapper fixes the path, not every administrator-supplied configuration choice; an administrator who can change startup configuration remains trusted.
5. Limits: user text uses the configured limit capped at 16,000 UTF-16 characters; references are capped at eight chunks/3,500 characters; individual embedding/source input is capped at 64,000 characters. JSON request limit is 300,000 bytes; response limit is 1 MiB; returned answer limit is 16,000 characters and embedding dimension at most 16,384 finite numbers. Connection timeout is three seconds; health timeout five seconds; other requests use the configured timeout capped at 180 seconds.
6. Each of the two transport instances admits at most two concurrent operations. Chat may retry once for an incomplete answer. This is local backpressure, not a per-user/distributed rate limiter or proof that Ollama cancels GPU work when a client disconnects.
7. Sanitization intentionally changes formatting and drops fenced examples. Valid questions about reserved model-control tokens may be rejected; operational guides containing them may be quarantined. This tradeoff suits a documentation/metrics assistant, not a code assistant. Review the source corpus before controlled reindexing.
8. Existing cache files are not automatically deleted or reindexed. Run reindexing only after confirming the VM endpoint and reviewing the accepted/quarantined source counts. Preserve restricted filesystem permissions and trusted artifact provenance.

## What remains outside a code-only guarantee

- Natural-language attacks without recognized markers, encoded/obfuscated instructions, and false factual claims may survive filtering. A model can still be persuaded to give a misleading answer. A source match or the existing grounded flag is not proof of factual entailment or authorization.
- Source labels and local cache content are not cryptographically authenticated by this patch. Treat cached sources as untrusted; secure the packaged source pipeline and cache permissions. Consider signed source manifests, integrity verification, and role-scoped retrieval if different roles should see different documents. [OWASP RAG security](https://cheatsheetseries.owasp.org/cheatsheets/RAG_Security_Cheat_Sheet.html).
- Neither prompt instructions nor removal of known password phrases constitutes complete secret detection. Do not index credentials, sensitive audit rows or role-restricted documents into a globally retrieved corpus.
- Model output remains untrusted presentation text. The service removes common active markup but this is not an HTML/XSS sanitizer. The UI must render text safely and must never execute model-generated SQL, code, links or commands. UI verification was outside scope.
- Source-code absence of a tool executor is the enforceable action boundary. Do not later bind model responses to repositories, shell execution, URLs or privileged workflows without independent server-side authorization and schema checks.
- No live Qwen adversarial evaluation, VM firewall review, TLS test, Ollama version check or database/VM mutation was performed in this task. These are release gates, not silently assumed passes.

## Verification

The new AiraPipelineSecurityTest uses a temporary loopback HTTP stub with synthetic data, not the deployed Ollama service or PostgreSQL. It tests destination rejection/acceptance, mutable-config isolation, fixed role/model structure, reserved control tokens and Unicode variants, fenced-code removal, poisoned references, mixed-question data exclusion, redirect refusal, proxy refusal, health pinning, embeddings, oversized responses, tool-call rejection and input limits.

Final Java 21 verification: mvn -o -q package succeeded; 137 tests discovered, 127 passed, 10 infrastructure-dependent/existing disabled tests skipped, zero failures/errors. All 36 new AiraPipelineSecurityTest cases passed. git diff --check passed. Tests requiring real application infrastructure remain opt-in.

No accepted reference chunks means no chat-generation call: Java returns an insufficient-reference answer. Model-failure fallback also uses only accepted chunks. Tests cover both cases. Filtering a chunk means excluding it from the active pipeline, not deleting the original source/cache file.

The isolated output/security/aira-ollama-hardening.patch contains only the six AIra service files, the new test class and this report. It excludes the earlier application-security changes. Apply only to a checkout without these AIra changes, starting with git apply --check; the current working tree already contains them.

## Release recommendation

Review this isolated service patch on securityscan. Validate the actual VM origin/certificate and legacy embedding contract, run a controlled corpus reindex, and evaluate representative benign and adversarial questions against the pinned Qwen model. Merge through a reviewed PR only after those gates. Do not describe the result as fully prompt-injection-proof.
