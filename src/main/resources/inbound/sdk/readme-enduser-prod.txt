DOORS WRITE SDK - PRODUCTION - Java 21+
Start with docs/index.html for this verified service contract, response reference and direct API instructions.
Extract the release ZIP into a private directory and open PowerShell there.
Place your own production PKCS12 identity at:
  Windows: $HOME/doors/security/doors-identity.p12
  Linux:   ~/doors/security/doors-identity.p12
The SDK expands ~/ to Java user.home; do not put literal $HOME in the properties file.
In doors-wallet.properties set DOORS_WALLET_PASS and, if needed, DOORS_WALLET_ALIAS.
A blank alias selects the sole private-key entry. An explicit alias must be a private-key
entry, not only a certificate. Inspect: keytool -list -keystore <your-wallet-path>
The release does not include a production private key or its password.
API key, HTTPS API base, service, instance and contract are prefilled. Upload only the
matching public certificate to this Write client, configure caller IPs, apply the database
service-scope grant and enable the mapping.
Run (new automatic-versioning contracts): java WriteClient.java sample.json
A publicly trusted server needs no PEM or custom truststore. For private CA HTTPS configure
DOORS_TRUSTSTORE_PATH, DOORS_TRUSTSTORE_PASS and DOORS_TRUSTSTORE_TYPE in the wallet file.
Environment variables override wallet values. Use forward slashes in Windows paths.
Expected version 0 creates a record. Updating version 1 requires expected version 1 and a
new UUID. After timeout retry the same file, expected version and printed UUID:
java WriteClient.java sample.json 0 <original-request-UUID>
Keep .doors-write-requests metadata and never blindly increment a version after a failure.
The ZIP contains your API key. Share only with the authorised application owner.

AUTOMATIC VERSIONING (new contracts only)
Run: java WriteClient.java sample.json
DOORS_AUTOMATIC_VERSIONING in the generated wallet indicates support.
DOORS compares the latest JSON using JSON content equality. Whitespace and object key order are ignored; array order and JSON value types matter. Unchanged JSON creates no new version. Changed JSON appends the next version within the administrator's per-client/service record limit. Contact the DOORS Administrator on VERSION_LIMIT_REACHED.
The SDK saves a request UUID internally before sending. After a timeout, rerun the same command with the same file; it reuses the pending UUID. Do not delete .doors-write-requests or change the pending payload before resolving the result. Only one SDK process may run in a kit folder at a time.
Explicit legacy syntax remains supported: java WriteClient.java sample.json expectedRecordVersion [requestUUID]. Existing contracts retain their previous behaviour until a separately approved migration.
