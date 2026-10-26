DOORS WRITE SDK - UAT - Java 21+
Start with docs/index.html for this verified service contract, response reference and direct API instructions.
Extract the release ZIP into a private directory and open PowerShell in that directory.
The release builder includes doors/security/doors-identity.p12 and matching client_public.pem,
and preconfigures doors-wallet.properties with the UAT wallet path and password.
The API key, HTTPS API base, service, instance and contract are prefilled by DOORS.
Administrator: map the bundled client_public.pem to this Write client, allow the caller IP,
apply its database service-scope grant, and enable the mapping before testing.
Run (new automatic-versioning contracts): java WriteClient.java sample.json
No server PEM is required for publicly trusted HTTPS certificates.
For a private CA, set DOORS_TRUSTSTORE_PATH, DOORS_TRUSTSTORE_PASS and
DOORS_TRUSTSTORE_TYPE in doors-wallet.properties to an administrator-provided truststore.
The signing identity and HTTPS truststore are different files with different purposes.
A blank wallet alias automatically selects the sole private-key entry. With multiple keys,
set DOORS_WALLET_ALIAS to the intended alias; inspect using keytool -list -keystore <path>.
Environment variables override wallet settings: clear stale DOORS_WALLET_* values if needed.
Legacy explicit-version mode only: for a new agreement use expected version 0. For updating version 1 use expected version 1
and a new request UUID. After timeout retry identical input with the original printed UUID:
java WriteClient.java sample.json 0 <original-request-UUID>
Keep .doors-write-requests metadata. Never blindly increment expected version on failure.
This UAT release contains private test credentials. Share only with its authorised tester.

AUTOMATIC VERSIONING (new contracts only)
Run: java WriteClient.java sample.json
DOORS_AUTOMATIC_VERSIONING in the generated wallet indicates support.
DOORS compares the latest JSON using JSON content equality. Whitespace and object key order are ignored; array order and JSON value types matter. Unchanged JSON creates no new version. Changed JSON appends the next version within the administrator's per-client/service record limit. Contact the DOORS Administrator on VERSION_LIMIT_REACHED.
The SDK saves a request UUID internally before sending. After a timeout, rerun the same command with the same file; it reuses the pending UUID. Do not delete .doors-write-requests or change the pending payload before resolving the result. Only one SDK process may run in a kit folder at a time.
Explicit legacy syntax remains supported: java WriteClient.java sample.json expectedRecordVersion [requestUUID]. Existing contracts retain their previous behaviour until a separately approved migration.
