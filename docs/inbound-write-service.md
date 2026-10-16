# Inbound agreement service

Implementation on `doors-write-feature`. Disabled by default. Existing read APIs,
Query Library rules, document downloads and read protocol settings are unchanged.

## What is implemented

- `POST /api/v1/inbound/agreements`, with full immutable JSON snapshots.
- Explicit client → service → instance → Agent mappings, managed in **Governance
  Hub → Inbound Services**. New mappings start disabled. Destination IDs cannot
  be edited through the UI; changing a destination requires operational reconciliation.
- A shared Agent write engine (`InboundDataset` adapters). Agreements is the first
  adapter; evaluations is deliberately rejected until its schema and business key
  are approved. No arbitrary table names, SQL, or database credentials are accepted
  from callers.
- PostgreSQL `json` payload plus tender/agreement/date/vendor/reference columns,
  current-version head, history and durable retry receipts.
- Separate target write credential with EXECUTE on a fixed database function only.
- Signed external requests, HMAC-protected Master-to-Agent requests and standard
  HTTPS certificate verification. This write contract is independent of read v1/v2
  Plain/AES settings: it requires a signature and sends JSON over HTTPS; an AES
  request/response envelope is not implemented for this first write service.

## Provisioning

1. Build and deploy Master, Agent and UI with writes disabled. The Master Flyway
   migration `V2026101601__inbound_write_registry.sql` creates only registry/audit
   tables in the Master database.
2. A DBA explicitly applies `agent-api/deploy/inbound/V1__agreement_inbound.sql`
   to each intended GePNIC PostgreSQL database. This is NOT an automatic Agent
   migration. It creates a new `doors_inbound` schema and does not change core
   tender tables. SQL has been tested on PostgreSQL 15; confirm target version.
3. Create a dedicated non-owner login role. Grant only schema USAGE and EXECUTE
   on the fixed function using the commented grants at the end of that migration.
   Create a separate GePNIC reader with SELECT on latest/history views as needed.
4. After confirming tender existence and submitting-client ownership in GePNIC,
   the DBA provisions exact tender grants (numeric client ID is the Master registry ID):

   ```sql
   INSERT INTO doors_inbound.tender_write_grant
     (instance_id, client_id, tender_id, enabled)
   VALUES ('tripura', 123, '1001', true);
   ```

   No grant means no write. The first implementation uses explicit grants; it does
   not guess GePNIC core-table names or automatically import tender ownership.
5. Configure an Agent-local write profile, using secrets from protected deployment
   configuration. No defaults reuse the existing read database login:

   ```yaml
   doors:
     inbound:
       enabled: false
       transit-secret: ${DOORS_INBOUND_TRANSIT_SECRET}
       targets:
         tripura:
           enabled: false
           jdbc-url: ${DOORS_INBOUND_TRIPURA_JDBC}
           username: ${DOORS_INBOUND_TRIPURA_USER}
           password: ${DOORS_INBOUND_TRIPURA_PASSWORD}
   ```

6. Configure the Master with the same randomly generated secret for that Agent
   (at least 32 characters; separate from API keys and existing transit secrets):

   ```yaml
   doors:
     inbound:
       enabled: false
       agent-secrets:
         "[GePNIC-Staging-1]": ${DOORS_INBOUND_TRANSIT_SECRET}
   ```

   The bracketed map key preserves the exact registered Agent ID under Spring
   property binding. Its existing base URL must use HTTPS; configure the JVM trust
   store if its CA is private. Never disable hostname/certificate verification.
   Reverse proxies must forward the new `/api/v1/inbound/` route to Master and the
   private `/v1/agent/inbound/` route to Agent. Limit ingress to the intended planes.
7. Register a mapping in the new UI. The instance ID must exactly match the Agent
   profile and target grant. Register the caller's RSA public certificate in its
   API client profile. Existing API subscription/calendar/IP checks also apply.
8. Enable the Agent target, Agent write feature, Master write feature and specific
   mapping only after staging acceptance. Restart after profile/secret changes;
   write pools are cached. Never rebind an instance to another database while
   request results remain uncertain.

## External contract and signature

All requests require X-API-KEY plus these headers:

| Header | Meaning |
| --- | --- |
| Idempotency-Key | UUID retained for retries |
| X-DOORS-Instance-ID | Approved instance, e.g. tripura |
| X-DOORS-Schema-Version | 1 |
| X-DOORS-Expected-Version | 0 for create; current record version for update |
| X-DOORS-Timestamp | UTC Unix seconds; maximum age 300 seconds, future skew 60 seconds |
| X-DOORS-Signature | Standard Base64 RSA SHA256 signature of the text below |

Sign the UTF-8 bytes of this exact newline-delimited text, with **no trailing newline**:

```text
DOORS-INBOUND-1
agreements
<instanceId>
<request UUID in canonical lowercase form>
1
<expectedVersion in decimal>
<timestamp in decimal>
<lowercase SHA256 hex of the exact UTF-8 JSON request body>
```

Use the standalone `tools/inbound/SubmitAgreement.java` example with JDK 21.
Required fields: tender_id, agreement_no, agreement_date. Keys are exact strings;
whitespace, control characters and unknown fields are rejected. The AgreementContract
allowlist defines optional fields from the supplied sample. Dates are ISO dates;
amounts remain strings, nonnegative, at most 16 integer digits and 2 decimal places.
The optional party objects permit only the supplied nested keys. JSON duplicate
keys, trailing commas and trailing values are rejected. Maximum raw payload is
1 MiB; nesting depth is 32. The supplied C:\DOORS\agreement.json requires removal
of its trailing comma before use; the server will never silently repair it.

201 means committed to the target integration schema, not GePNIC business approval.
200 with replayed=true returns the original stored version. A changed body or
metadata under the same request ID produces 409. A stale expected version also
produces 409. Updates are full snapshots, never patches. Same data with a new
request ID and correct expected version creates a new version.

For an uncertain result (503/504 WRITE_RESULT_UNKNOWN), retry the SAME request ID,
instance, schema version, expected version and original body. Refresh timestamp
and signature if needed. Timestamp is deliberately excluded from the retry
fingerprint. Do not automatically retry a 409 with a new ID or expected version.

## Reading and operation

```sql
SELECT record_version, schema_version, payload
FROM doors_inbound.agreement_latest
WHERE instance_id = :instance AND tender_id = :tender
  AND agreement_no = :agreement;

SELECT record_version, schema_version, payload
FROM doors_inbound.agreement_version
WHERE instance_id = :instance AND tender_id = :tender
  AND agreement_no = :agreement AND record_version = :version;
```

Master allows 16 concurrent forwarded writes; each Agent allows 8 total and two
connections per destination. Database lock wait is 5 seconds, query timeout 15
seconds, Master request timeout 25 seconds. Existing request rate limiting applies
in addition. Tune only after mixed read/write load tests. Never rely on a master
audit insert for transaction truth: target receipts are authoritative if logging
or the reply fails. Master shows its latest 100 forwarded outcomes without payloads.

Disable the mapping or global flag to stop new requests; retain history/receipts.
Back up all target tables consistently. Revoking a tender grant also denies retries
for that tender; reconcile outstanding receipts with an authorised operator.
Changing/removing accepted keys and purging idempotency receipts are not supported
in this phase. Production ownership validation automation, business approval,
evaluation schema, batch writes, and AES envelopes need separately approved work.

## Verification

Run Master focused tests with JDK 21. Agent unit tests run with `mvn test`.
To include PostgreSQL concurrency/permission tests, provision an **isolated** test
database with the migration and roles `doors_test` (owner) and
`inbound_test_writer` (EXECUTE only), then set DOORS_INBOUND_TEST_JDBC.
The integration tests use those test role names and must never target a live DB.
The two copies of org.gepnic.doors.inbound are a versioned wire-contract snapshot:
keep them identical until the repositories adopt a shared Maven module.

### Local verification completed — 16 October 2026

- Agent: 14 tests passed, including six PostgreSQL tests for immutable history,
  concurrent requests, idempotent retries, permission enforcement and rollback.
- Master: 41 focused tests passed, including existing read-protocol, download
  signature, JWKS, subscription and security regression checks.
- The HTTPS integration test used a real Agent and isolated PostgreSQL 15 database:
  create, retry, conflicting retry and version update passed. Master registry and
  client routing were test fixtures; this was not a deployed full-stack acceptance test.
- UI production build and browser registration/activation checks passed with mocked APIs.
- The standalone signed Agreement client compiled with JDK 21.
- No production or staging destination database was modified.

For the optional HTTPS integration test, set DOORS_INBOUND_E2E_AGENT,
DOORS_INBOUND_E2E_TRUST (test PKCS12, test-password) and DOORS_INBOUND_TEST_JDBC.
Provision only isolated test instances and the test roles described above.
Before enabling a staging mapping, validate the real client subscription, TLS chain,
tender grants and routing with a designated test agreement.

Build outputs: UI dist and Agent target JAR were rebuilt. Master executable JAR is
in target/inbound-build/masterapi-0.0.1-SNAPSHOT.jar because Windows prevented
replacement/repackaging of the existing target JAR. Use the isolated build output
for this feature; do not deploy the standard target JAR from the failed package attempt.
