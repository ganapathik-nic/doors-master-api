# Governed inbound JSON write services

## Scope and checkpoints

Branch: doors-write-feature.
Pre-redesign checkpoints: UI c92ebdb; Master c0cdb6a; Agent e4f0d47.
The existing read submission components, SELECT-only SQL validator, read gateway
and document-download implementation remain in use. Query and write approvals
share the same pending-state and independent-reviewer rule.

New proposals support JSON-driven definitions with reviewed business keys and
extracted scalar columns. Existing Agreement schema 1 contracts retain their legacy
validation and storage. The generator creates dedicated integration tables; it never
writes directly into arbitrary existing GePNIC business tables. See the JSON-driven
write definitions section below for supported input, deployment and grant rules.

## User workflow

1. Request Submission â†’ Write JSON. Enter title, purpose and sample JSON.
   Developers must supply an approved data request ID, as with read proposals.
   Uploading a file is supported. The sample must be valid JSON (no trailing comma).
2. Analyse the sample, select its business-key fields, and review the table preview.
   Confirm the selected keys and extracted fields, then generate the proposal.
   The generated definition includes fixed parameterized SQL and downloadable DDL.
   The proposer submits a fixed draft. While pending, an independent reviewer may
   revise columns, types, keys and the sample, with mandatory remarks and retesting.
3. Select an Agent registered as a sandbox and run Sandbox Test.
   The Agent uses its separately configured sandbox credential. It creates a unique
   temporary schema, provisions tables/function, writes the sample, reads it back,
   and rolls back the entire transaction. No operational destination is provisioned.
   Evidence binds the exact sample hash and definition hash to the proposal.
4. Submit for approval. Submission without successful sandbox evidence is rejected.
5. Request Approvals â†’ Write JSON. A different DataManager reviews the sample,
   generated DDL/write operation and sandbox evidence, then approves or rejects
   with remarks. The proposer cannot approve or reject their own proposal.
   Approved entries appear in Write Library. Rejected entries remain as history;
   create a new proposal to revise them.
6. Write Library: select an approved contract and register its Agent/instance.
   A DBA applies the approved SQL using a separate owner identity. New services
   use the shared doors_write schema with service-specific tables/functions.
   Existing matching objects are verified, not recreated. Schema changes to an
   already provisioned service require a separately reviewed DBA migration.
7. Configure an EXECUTE-only runtime credential and click Verify schema and
   permissions. Verification checks the function definition/security, JSON column,
   version constraints and least-privilege runtime account.
8. API Client Registry â†’ Write Services: assign a verified approved destination
   to an existing API client. New assignments start disabled; enable explicitly.
   Existing client public keys, API keys and IP whitelists remain in the existing
   client registry. Existing client access scheduling/interceptor checks still run.
   Read permission does not automatically grant write permission.
9. Submit signed JSON to POST /api/v1/inbound/{service-code}.
   Existing legacy Agreement clients continue to use /api/v1/inbound/agreements.

The schema migration disables all legacy ungoverned write assignments. Those
cannot be re-enabled without an approved, verified deployment. A disabled legacy
mapping can be attached to that deployment only when service, instance, Agent and
client remain identical. Existing governed mappings stay pinned to their contract;
replacing them requires a separately reviewed migration, not silent reassignment.

## Destination data

Business key: instance + tender_id + agreement_no.
Extracted columns: agreement_date, party_1.vendor_id, party_2.user_name,
tender_nit_ref_no and loa_ref_no, plus business keys and system metadata.
Full original JSON is retained in a PostgreSQL json column. Each accepted change
adds an immutable snapshot. Contract versions and record versions are separate.

Runtime writes require a tender_write_grant for the exact instance/client/tender.
After verifying tender existence and ownership, the DBA provisions the grant:

    INSERT INTO doors_inbound.tender_write_grant
      (instance_id,client_id,tender_id,enabled)
    VALUES ('tripura',123,'1001',true);

No automatic lookup against unconfirmed GePNIC business-table names is implemented.

## Configuration

Master:

    doors:
      inbound:
        enabled: false
        agent-secrets:
          "[EXACT-AGENT-ID]": ${DOORS_INBOUND_TRANSIT_SECRET}

Agent:

    doors:
      inbound:
        enabled: false
        governance-enabled: false
        transit-secret: ${DOORS_INBOUND_TRANSIT_SECRET}
        sandbox:
          enabled: false
          jdbc-url: ${DOORS_INBOUND_SANDBOX_JDBC}
          username: ${DOORS_INBOUND_SANDBOX_USER}
          password: ${DOORS_INBOUND_SANDBOX_PASSWORD}
        targets:
          tripura:
            enabled: false
            jdbc-url: ${DOORS_INBOUND_TRIPURA_JDBC}
            username: ${DOORS_INBOUND_TRIPURA_USER}
            password: ${DOORS_INBOUND_TRIPURA_PASSWORD}

Enable governance only on participating Agents. Sandbox must use an isolated
test database and a schema-creation credential, never the operational runtime
credential. Enable the destination profile for verification; global runtime writes
can remain disabled until acceptance.

Master/Agent secrets must match, be random and at least 32 characters.
Agent URLs require HTTPS; use a JVM truststore for a private CA.
Do not disable hostname or certificate verification.
Master forwards signed control requests to /v1/agent/inbound/governance/sandbox
and /verify. These endpoints require HMAC and a fresh timestamp.
Runtime endpoints independently validate payloads, grants and schema permissions.

Master Flyway adds registry/audit and governance tables:
V2026101601__inbound_write_registry.sql
V2026101602__write_governance.sql
No GePNIC target table is automatically created by a Master migration.

The DBA target migration is agent-api/deploy/inbound/V1__agreement_inbound.sql.
Runtime roles receive schema USAGE and function EXECUTE only, as shown there.
GePNIC readers may receive SELECT on agreement_latest and agreement_version.

## Signed submission and retries

The existing tools/inbound/SubmitAgreement.java JDK 21 example remains usable.
Request headers:
X-API-KEY, Idempotency-Key (UUID), X-DOORS-Instance-ID,
X-DOORS-Schema-Version (1), X-DOORS-Expected-Version,
X-DOORS-Timestamp (UTC Unix seconds), X-DOORS-Signature.

RSA SHA256 signs these UTF-8 lines with no final newline:

    DOORS-INBOUND-1
    agreements
    <instance>
    <canonical request UUID>
    1
    <expected record version>
    <timestamp>
    <lowercase SHA256 of exact raw JSON bytes>

Expected version 0 creates a record. For an update, send the current version and
a new request ID. HTTP 201 confirms committed storage, not GePNIC business approval.
An identical retry returns HTTP 200 with replayed=true and the original version.
Changed content under the same ID, or a stale expected version, returns HTTP 409.
After an uncertain result, retain the exact body, destination, expected version and
request ID. Refresh only timestamp/signature. Do not blindly create a new ID.

The write transport is signed JSON over HTTPS, independent of read Plain/AES
settings. AES write envelopes and batch writes are not implemented.

## Reading

    SELECT record_version, schema_version, payload
    FROM doors_inbound.agreement_latest
    WHERE instance_id=:instance AND tender_id=:tender
      AND agreement_no=:agreement;

    SELECT record_version, payload
    FROM doors_inbound.agreement_version
    WHERE instance_id=:instance AND tender_id=:tender
      AND agreement_no=:agreement
    ORDER BY record_version;

## Verification and local operation

Agent tests cover sandbox rollback, runtime-account verification, signatures,
JSON validation, immutable snapshots, concurrency, idempotency and permissions.
Master tests cover governance transitions, independent approval, evidence binding,
destination gating, explicit mapping, security roles and CSRF.
The optional WriteGovernanceAgentIntegrationTest runs the lifecycle and a signed
write/retry against a real HTTPS Agent and isolated PostgreSQL database.
Read protocols, document manifests/downloads/signatures, JWKS, rate limits and
host-plane rules have separate regression checks.
Browser tests cover the workflow tabs and governed client registration.

Database tests require DOORS_INBOUND_TEST_JDBC against an ISOLATED provisioned DB.
HTTPS integration also requires DOORS_INBOUND_E2E_AGENT and
DOORS_INBOUND_E2E_TRUST. Test fixtures use doors_test and inbound_test_writer;
never aim them at an operational database.

Current local UI: https://doorsadmin.doors.test:5173/doors/
Updated local Master: 127.0.0.1:8054
Write Agent: https://localhost:55441
Isolated target PostgreSQL: 127.0.0.1:55439
The older Master on port 8052 is separate. The local Agreement test mapping remains
disabled until the new governance process is completed. The previous direct SDK
test will therefore be denied until approval, verification and assignment.

The three copies of agreement-provision.sql (deployment plus embedded resources)
and shared inbound Java definitions must be kept identical.

Verification completed on 16 October 2026: governed lifecycle PostgreSQL tests,
real HTTPS Agent governance/write/retry integration, read and document regression
checks, both browser walkthroughs, and all 61 UI security tests passed.
One existing curl test is disabled on Windows; its other five tests passed.
UI dist, Agent JAR and Master target/inbound-build executable JAR were rebuilt.
The current local registry has no automatically approved proposals; the
Local-Agreement-Test mapping remains disabled for manual workflow acceptance.

### Write Library catalogue and availability

Migration V2026101603 adds category/subcategory metadata and the DISABLED
contract status. The Write Library provides Approved, Disabled and Rejected tabs,
search, sorting, pagination, definition and evidence inspection, catalogue
classification, destination mappings, verification and audit history. Pending
proposals remain in Request Approvals. New proposals start in Request Submission.

Catalogue edits change classification only; the approved schema and sample remain
immutable. Disabling a contract blocks new requests and disables its client
assignments, without deleting stored records or history. Already admitted requests
may finish. Re-enabling the contract does not reactivate client assignments; these
must be explicitly enabled in API Client Registry after destination verification.
Contract locking serializes availability changes with assignment activation.

The library endpoints and mutations are restricted to governance managers, with
CSRF protection on mutations. Existing Query Library and read endpoints are not
changed by this library enhancement.

### JSON-driven write definitions (16 October 2026)

New Write JSON proposals use deterministic local analysis, not an external AI
service. Upload/paste a single JSON object, select Analyse JSON, review the detected
scalar fields, choose 1â€“6 business-key fields, and preview the generated SQL before
confirming the proposal. Likely ID/number/code fields are suggestions only: one
sample cannot establish business uniqueness. The suggested service code is editable.

Strings stay text (including dates, leading-zero IDs and decimal strings); JSON
numbers become numeric, booleans become boolean. Null-only samples default to
optional text. Nested object scalars are extracted using JSON Pointer paths; arrays
stay in the full payload. Duplicate JSON properties, trailing commas and root arrays
are rejected. Keys must be nonblank strings or whole JSON numbers; non-key fields
are optional. New unknown fields remain in the complete JSON. Change the proposal
if a field needs a different inferred type. Up to 100 scalar fields are supported.

Master regenerates the definition from the sample and chosen keys. Browser-supplied
SQL is never accepted. The reviewed definition is immutable and bound to sandbox
evidence, independent approval, destination verification and client assignment.
New definitions use doors_write.<service_with_underscores>, without a _version
suffix. Each service has its own <table>_latest view, <table>_head,
<table>_receipt and <table>_service_grant tables and store_<table> function.
The record_version column retains immutable version history. Existing stored
definitions without storageVersion retain their original hash-based schema and
_version table; no automatic migration or client reassignment occurs.

Apply the reviewed SQL with a dedicated schema owner. Give the Agent runtime role
only schema USAGE and EXECUTE on the generated store_<table> function; grant the
GePNIC reader SELECT separately. Provision <table>_service_grant for the approved
instance_id and client_id, with enabled=true. This authorises all business keys
for that service and instance. Every incoming write requires the approved mapping,
signed request and matching service scope. Retries retain
idempotency and expected-version checks, with full immutable JSON snapshots.

Deployment requires the updated UI, Master and Agent. Master migration
V2026101604 permits dataset-specific service codes. The existing read and document
APIs do not use this generator or these tables.


### Editable field types

The write-submission field grid supports the listed PostgreSQL types, including
Date, JSON/JSONB and arrays. Preview validates the sample conversions. Readable
column names are suggested and editable, with identifier and uniqueness checks.
Only detected JSON paths and supported types are accepted. Changes clear the
preview and confirmation.
The selected types are included in the immutable definition and its fingerprint.
Text preserves leading-zero identifiers. Numeric and boolean selections can accept
compatible JSON strings without rewriting the retained original JSON. Boolean string
values must be exactly true or false. Numeric business keys must be canonical whole
numbers; use Text to preserve padded IDs. Invalid conversions are rejected before
sandbox testing. Existing approved definitions cannot be edited in place.


### Sandbox routing and PostgreSQL type catalogue

The dry-run selector uses only active Agents explicitly registered as sandbox
Agents. The server independently enforces the sandbox flag. Deployment mapping
continues to show all active Agents. Governance and runtime write traffic may use
`doors.inbound.agent-urls[agent-id]` to select a separate HTTPS write endpoint;
ordinary read traffic continues to use the registered Agent base URL. HTTPS and
transit authentication remain mandatory. The local Dev-01 write override points
to https://localhost:55441 with the isolated rollback-only sandbox profile.

The field selector now exposes PostgreSQL 15 built-in data storage types, including
date/time, UUID, JSON/JSONB, binary, network, geometry, search, ranges/multiranges,
and their array forms. Serial/identity represents database-generated values, and
custom enums/domains/composites require a database-specific definition; they are
not accepted as free-form SQL types. Length/precision modifiers are not yet editable;
character and bit use their default length of one. Type names are allowlisted.

Objects and arrays are detected as JSON columns as well as retaining the full
original payload. For expanded types the generated writer uses PostgreSQL native
record conversion, with explicit ISO date style, UTC time zone and C monetary
locale. Preview rejects invalid conversions; the target sandbox validates using
its own PostgreSQL installation. Existing three-type definitions retain their
original generated-column DDL. Extended definitions use typed ordinary columns
populated only by the approved function; verification checks their types, function
body, conversion settings and EXECUTE-only runtime privileges.


### Shared schema and reviewer revisions (17 October 2026)

For service agreement-data, the destination is doors_write.agreement_data and
its runtime function is doors_write.store_agreement_data. Evaluation services
use their own tables/functions in the same schema. Provisioning one service does
not revoke another service's table grants. Generated SQL intentionally fails on
existing tables instead of silently overwriting them. Never run initial setup SQL
as a migration over populated tables.

Current Status shows draft, pending review, approved or rejected. Approval is
separate from destination readiness: DBA setup, verification and client activation
must still be completed. The runtime verifies the existing definition and executes
its function; it does not execute DDL or insert directly into the tables. Only the
separately configured sandbox creates temporary objects and rolls them back.

Pending reviewers can edit columns/types/business keys/sample. They cannot change
the service code or approve their own submission. Changes are audited with the
before/after definitions and samples. Saving invalidates sandbox evidence; a fresh
sandbox test is mandatory. Approval checks the definition fingerprint so a stale
browser cannot approve an older structure. Approved definitions remain immutable.

### Proposed public JSON contract

The files in write-contract-examples separate three concerns:

- agreement.sample.json: valid sample business data, preserving the received values.
- agreement.schema.json: proposed JSON Schema Draft 2020-12 validation rules.
- agreement.mapping.example.json: DOORS-specific PostgreSQL column mapping; this
  is not an industry-standard schema and must be reviewed/generated through DOORS.

JSON data does not carry type declarations. Strings for dates and identifiers are
normal. A trailing comma is a syntax error. JSON Schema describes required fields,
types, nested objects, dates, limits and the policy for unknown fields. The proposed
example requires the two keys, agreement date and both parties. Other required
fields, lengths, currencies and retention rules require business-owner agreement.
Decimal amounts remain strings with an explicit decimal pattern to preserve the
source values/scale; PostgreSQL maps them to numeric. Numeric JSON amounts are also
possible if the consumer contract consistently supports exact decimal arithmetic.
Do not use floating-point storage for these amounts.

Publish the agreed request/response/security contract as OpenAPI 3.1 using the
JSON Schema dialect. Enable date-format assertions; format is otherwise commonly
annotation-only. Full JSON Schema enforcement (required non-key fields, bounds,
additionalProperties and cross-field business rules) is a proposed next step, not
part of the current sample-driven PostgreSQL mapping validator. Do not advertise
the example's stronger rules as currently enforced by DOORS.

Keep contract/schema version separate from record_version. Retain requestId for
idempotent retries and expectedVersion for optimistic concurrency. Client identity
and destination authorization come from authenticated registration, never from
untrusted business payload fields. The existing signed write protocol is unchanged;
the sample file is its business payload, not a complete signed request envelope.

Sources: https://json-schema.org/understanding-json-schema/reference/type,
https://json-schema.org/understanding-json-schema/reference/object,
https://spec.openapis.org/oas/v3.1.


### Optional extracted columns and proposal panel

New proposals suggest only scalar leaf fields. Nested party objects and arrays
remain in the complete original payload and do not become additional JSON columns.
The selected-fields list is validated server-side: only discovered scalar paths
are accepted, all business keys must be retained, and additional JSON/JSONB
extraction columns are rejected. Previously approved definitions remain unchanged.

Before submission, the draft owner can edit or remove optional extracted columns.
While pending, the independent reviewer can do so. Both actions preserve the sample,
regenerate SQL, record an audit event and invalidate sandbox evidence. Approved
contracts cannot be edited. Removing a column never removes data from the payload.

Open displays a focused side panel with proposal title, status, definition and
actions. Structure editing stays in the panel; removed columns can be restored
before saving. Deselect a business-key field before removing its column. GePNIC
can read the complete payload by the retained keys or use extracted columns.

### Independent Write Services applications and versions

API Client Registry > Write Services now supports creating a separate application,
API key, public signing certificate, source-IP allowlist and activation state.
New clients start inactive and receive no read or write grants automatically.
Existing identities can be explicitly attached; their credentials and activation
remain shared, so changes affect both uses. Prefer separate identities when the
external read and write applications differ. Existing mapped clients are retained
with environment UNSPECIFIED; migration does not change credentials or grants.

There are three separate versions:
- API protocol version: currently protocol 1, signed JSON over HTTPS.
- JSON contract version: the approved definition pinned to the verified mapping.
- Record version: successive immutable snapshots for one business key, 1, 2, 3.

New SDK and Swagger requests send X-DOORS-Protocol-Version and
X-DOORS-Contract-Version. Mismatches are rejected. Legacy requests omitting those
headers continue using their pinned mapping. X-DOORS-Schema-Version remains the
legacy transport-envelope marker (1), not the approved contract version.
An upgrade requires explicit approval and destination migration; selecting an
arbitrary new version does not alter tables or replace an existing mapping.

Each mapped service offers API Guide / SDK, Swagger, and Write access scope.
The standalone Java 21 SDK contains source, sample JSON, OpenAPI and a client-specific
properties file containing the registered API key and configured API base URL.
It includes no private key or wallet password.
Configure the approved HTTPS API base URL and local identity before execution.
Swagger signs locally using the user's PKCS12 identity; the identity and password
are not uploaded. Closing the panel clears their in-memory references.
Write access scope generates service/instance/client permission SQL for the
DBA to review and apply; the API does not execute provisioning SQL.

Use expected record version 0 to create, and the current version to append a new
snapshot. A new change uses a new request UUID. Retry an uncertain result with the
same UUID, exact payload and expected version. Retries do not create a new snapshot.
The full payload is retained; extracted columns remain optional. OpenAPI includes
the approved sample; it does not claim complete JSON Schema validation.

Local validation (2026-10-18): Master API 8054 runs the write-client build; the
additive profile migration has been applied. The Vite development UI on 5173
serves the new screens. The production UI build is output/write-client-build;
it has not replaced the nginx static deployment. Before deploying Write Swagger,
the portal Content-Security-Policy connect-src must permit its configured machine
API origin, and that origin must allow the portal in CORS. The existing nginx
static policy is self-only; do not relax it globally or disable CSP.

Verified against Development / Dev-01: protocol 1 + contract 5 replay returned
200 and recordVersion 1; contract 4 returned 422. The existing agreement retained
one stored snapshot. Browser tests verify independent registration/security,
ordered grant generation, and local Swagger signing with an ephemeral test key.


Write integration prefill update: service actions are bordered buttons, with a
direct Download SDK action. API Guide and Swagger show the registered API key
masked, with reveal/copy controls. Swagger preauthorises this key and fills the
destination, protocol, contract, timestamp and signature automatically. Only the
request UUID, expected record version and payload are editable request inputs.
The downloaded SDK includes the API key and UI-configured HTTPS machine base URL
in doors-wallet.properties; keep this client-specific ZIP private. Its download
response uses Cache-Control: no-store. Local PKCS12 credentials are never bundled.


### Service and instance scope update (2026-10-18)

For JSON-driven write services, per-agreement grants are removed. The service's
own `<table>_service_grant` authorises `(instance_id, client_id)`; a permission for
one service or instance cannot authorise another. Read access remains separate.
The earlier legacy `doors_inbound` section is historical and does not describe
this JSON-driven runtime.

For an existing deployment, download **permission upgrade SQL** under **Write
access scope** and have the DBA execute it before enabling the intended scope.
It creates the service permission table and replaces the store function, retaining
record history, receipts and existing function EXECUTE permissions. Old exact-key
grants are not copied into broader permissions; the old table is retained unused.
Then apply the explicit scope SQL and reverify the destination. New DBA schema
scripts already use the new permission model. Runtime APIs never create tables.

The SDK accepts `java WriteClient.java sample.json 0` for a new agreement. It
creates a UUID and saves credential-free retry metadata in `.doors-write-requests`
before sending. For another snapshot, use the current record version and a new
UUID. After an uncertain result, pass the saved UUID as the third argument with
the original payload and expected version. Reusing a UUID with changed local
inputs is rejected. Invalid UUID text produces a concise usage message.

Local validation: Master and Agent scope builds are running, and the nginx UI
has been updated. Development/Dev-01 client 30 has explicit agreement-data scope.
A new test agreement created versions 1 and 2, replay returned 200, stale version
returned 409, and stored JSON matched the submitted data. The original agreement's
versions 1, 2 and 3 were preserved. Backend regression and full governance-flow
tests passed, including read protocol and document-download checks.

SDK HTTPS trust can optionally be configured in doors-wallet.properties using DOORS_TRUSTSTORE_PATH, DOORS_TRUSTSTORE_PASS and DOORS_TRUSTSTORE_TYPE (default PKCS12; JKS also supported). A blank path keeps JVM defaults and existing -D options. Environment variables override wallet values; a configured path overrides JVM truststore settings. Use forward slashes in Windows paths. Relative paths resolve from the working directory. The configured store replaces JVM default trust for this process, with certificate and hostname verification retained. The signing wallet remains separate. No truststore secrets are bundled in generated SDKs.

Write Library downloads now combine object creation and grants in one transaction. Existing roles doors and gepuser are required: doors receives schema USAGE, write-function EXECUTE and SELECT on the latest view; gepuser receives USAGE and SELECT on the history table and latest view. No public-schema privileges or global default privileges are changed. A pre-commit guard rejects effective direct-write or schema-CREATE privileges for doors. The service/client/instance scope remains an explicit approval step. Stored contract specifications and hashes remain unchanged; downloadable DDL is regenerated from the specification for existing library entries. Sandbox runtime DDL remains separate from deployment role grants.

DBA installation scripts now create the cluster-wide doors LOGIN only if absent, with NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOREPLICATION/NOBYPASSRLS. They grant CONNECT only to the current database. An existing doors role and password are left unchanged. The DBA sets a new role password separately using psql \password doors. gepuser must already exist. Role creation requires an authorised DBA; subsequent service scripts reuse the role. This does not automatically grant SELECT on public tables or broaden existing read permissions.


## Automatic versions and unchanged JSON (storage definition 3)

New proposals use storage definition 3. Existing approved definitions (null/2), hashes, installed functions and explicit-version SDKs remain unchanged. Do not rerun a new-installation SQL on an existing service: use a separately reviewed migration, or a fresh staging service/database. Deploy Agent API, Master API and UI together before testing a new contract.

For new contracts `java WriteClient.java sample.json` generates and retains an internal UUID and sends expected version -1. The database locks the record head, compares the incoming payload with the latest payload using jsonb equality, and returns UNCHANGED without a new snapshot if equal. Object order/spacing are ignored; array order and types matter. A changed payload appends the next version. Explicit expected-version calls still work; stale changed payloads fail. Replay receipts remain authoritative even after later updates or a lowered limit.

Write Services > service mapping > Versions per record configures a client/service/destination limit; 0 in the form means unlimited (NULL). It compares against total versions of the record, including versions submitted by other clients. The trusted Master sends this policy in the MAC-protected Agent envelope, never accepting a client-supplied limit. The stored function enforces it under the same record lock and returns DW004 / VERSION_LIMIT_REACHED. Unchanged JSON and successful replays work even when the limit is reached. Reducing a limit does not delete history.

The new latest view contains instance_id, selected record-key columns and payload. Only payload is JSON; other extracted fields and version metadata remain in the history table. DBA/runtime permissions stay restricted.

The SDK serializes processes within one kit folder. Pending metadata is scoped to endpoint, instance, contract and API-key digest. A timeout/unknown result retains the UUID; the same simple command retries it. A changed file is blocked until the pending request is resolved. Definite responses clear the pending pointer, while per-request metadata remains. Never delete pending metadata to escape an unknown result.
