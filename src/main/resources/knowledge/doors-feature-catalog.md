# DOORS Feature Catalogue

## Rate limiting

DOORS rate limiting protects interactive screens and machine APIs from bursts, accidental loops and abusive clients. Limits are applied before expensive orchestration work. When a bucket is exhausted, DOORS returns HTTP 429 Too Many Requests; the caller must wait for the indicated window and retry rather than immediately looping. Rate limiting is separate from write-record version limits: rate limits control request frequency, while a write version limit controls the number of changed snapshots retained for one business key. Identical write JSON returns UNCHANGED and does not consume a record version.

Administrators troubleshoot rate limits by identifying the caller, API client, source IP, endpoint, time window and trace ID in Execution Logs. A client-specific integration must use backoff and must not bypass the gateway. Document-download endpoints may have deliberately different policies because large streamed transfers are governed by download authorization, receipts and integrity checks.

## Three planes

DOORS uses three isolated request planes. The Admin Plane serves governance and administration for authorised human users. The External Plane serves external and reporting users without exposing administration. The API Plane serves machine-to-machine gateway, write and document operations. A hostname or route used on the wrong plane fails closed; authentication on one plane does not grant routes on another.

## Read services

Read services execute only approved parameterised SELECT templates through mapped Agents. The caller, query and Agent scopes must intersect. API keys, registered public keys, IP allowlists, client activation, query authorisation and Agent scope are independent controls. SQL changes require review and approval.

## Write services

Write services receive approved JSON contracts and store versioned records through a DBA-installed function. The live API never creates tables. The lifecycle is: submit and analyse sample JSON; select the record key and extracted columns; run sandbox write/read-back; obtain independent approval; download DBA SQL; install it in the GePNIC database; map and verify each Agent; authorise the API client; apply service permission; then test using the UAT SDK or Swagger. AES-256-GCM protects the payload and RSA signatures authenticate requests.

## Documents

The document service discovers eligible files using an approved manifest query, evaluates the configured download policy, streams allowed files and records download audits and receipts. SHA-256 comparison verifies destination integrity. A document count question is answered from live audit data, not from this reference text.

## Agents

An Agent represents one governed GePNIC database destination. Read queries and write services can use the same Agent while retaining independent permissions. Write verification checks that the approved schema, tables, view, function signature, function ownership and runtime grants match the approved contract.

## API clients and credentials

An API client has an API key, registered public key, source-IP allowlist and activation state. These controls identify and authenticate the calling application; they do not themselves authorise a query or write service. Service mappings and permissions remain separate. Private keys and wallet passwords remain with the application owner and are never uploaded to DOORS.

