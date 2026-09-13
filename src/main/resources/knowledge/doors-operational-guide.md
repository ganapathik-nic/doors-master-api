# DOORS PLATFORM OPERATIONAL GUIDE & SOP MANUAL

## 1. System Overview & Architecture
The Data Orchestration and Operational Reporting System (DOORS) is an enterprise data bridge and governance platform built for the Government e-Procurement System of National Informatics Centre (GePNIC).
DOORS connects distributed GePNIC regional database nodes with central analytical consumers, external portal users, and third-party systems without exposing raw database credentials, direct database ports, or full database access.

### The Three-Plane Architecture
DOORS enforces strict plane separation via `PlaneRoutingFilter` and Nginx reverse proxies:
1. **Admin Plane** (`doorsadmin.gepnic.nic.in` / `doorsadmin.doors.test`):
   - Exposes administrative and governance interfaces: User Management, Query Governance, Template Contracts, Infrastructure Agents, Document Download Policies, PKI Signing Keys, and Unified Audit Logs.
   - Authorized Roles: `DataManager`, `ADMIN`, `SecurityAdmin`, `Developer`.
   - Rejects unprivileged or external user roles.
2. **External Plane** (`doorsexternal.gepnic.nic.in` / `doorsexternal.doors.test`):
   - Exposes self-service workflows: Data request submissions, personal submission status tracking, and public analytical reports.
   - Authorized Roles: `External`, `DataViewer`, `ApiUser`.
   - Rejects administrative routes and gateway execution calls.
3. **API Plane** (`doorsapi.gepnic.nic.in` / `doorsapi.doors.test`):
   - Dedicated exclusively to machine-to-machine integrations and automated consumers.
   - Exposes: Gateway orchestration (`/api/v1/master/gateway/**`), Document streaming downloads (`/api/v1/master/gateway/documents/**`), and OpenAPI Swagger sandboxes (`/v3/api-docs`, `/doors-swagger.html`).
   - Rejects browser-based interactive user login sessions.

If an incoming request host does not match an assigned plane, DOORS immediately fails closed with HTTP status `421 Plane Unknown` (`DOORS-PLANE-UNKNOWN`).

---

## 2. Roles & Segregation of Duties (RBAC / SoD)
DOORS enforces strict separation of duties to prevent any single actor from authoring, approving, and consuming sensitive data:

### Data Manager (`DataManager`)
- **Primary Duties**: Platform governance authority. Approves data pull requests, reviews and approves SQL query templates, manages execution agents, registers API clients, configures document download policies, and investigates audit log exceptions.
- **Boundaries**: Must not author and self-approve the same control item. Never has direct raw access to underlying database credentials or unapproved arbitrary SQL.
- **Security Requirement**: Privileged access requires NIC VPN confirmation (`vpnStatus` must be `CONFIRMED` or `GRACE_PERIOD`) and TOTP Two-Factor Authentication (MFA).

### Security Administrator (`SecurityAdmin`)
- **Primary Duties**: Privileged user lifecycle management, account onboarding, role assignments, VPN entitlement approvals, and password resets.
- **Boundaries**: Does not participate in day-to-day data query approvals or operational report authoring. Separation between identity governance (SecurityAdmin) and operational governance (DataManager).

### Developer (`Developer`)
- **Primary Duties**: Translates approved data requirements into parameterized SQL query templates. Executes dry-runs against authorized sandbox agents.
- **Boundaries**: Cannot approve their own query templates for production use. All template changes require Data Manager approval. Read-only parameterized SQL only; DDL, DML, and arbitrary table access are strictly forbidden.

### Data Viewer (`DataViewer`)
- **Primary Duties**: Accesses approved analytical reports through the interactive Report Viewer. Runs parameterized reports within authorized time windows and exports data to CSV, Excel, or PDF for official duties.
- **Boundaries**: Read-only consumption. Cannot view SQL text, modify parameters outside allowed bounds, or access administrative menus.

### External User (`External`)
- **Primary Duties**: Third-party department users who submit data request proposals justifying business purpose, required fields, date ranges, and frequency.
- **Boundaries**: Submissions enter a `PENDING` queue. Cannot access reports until formally approved by a Data Manager.

### API User (`ApiUser`)
- **Primary Duties**: Machine consumer representatives managing automated system-to-system integrations. Configures client public certificates and tests SDK endpoints in active Swagger sessions.

---

## 3. Query Lifecycle & Matrix-Based Agent Authorization

### Query Template Lifecycle
1. **Submission**: A Developer creates a parameterized SQL template (e.g. `SELECT ... WHERE tender_id = :p_tender_id`). Status: `PENDING`.
2. **Dry-Run Validation**: Developer tests the template against an authorized sandbox execution agent. Dry-runs evaluate execution time, parameter casting, and limit output to 5 rows.
3. **Governance Review**: A Data Manager inspects the SQL structure, parameters, category, and target agent scope.
4. **Approval**: Upon approval, the template status becomes `APPROVED`. The template contract schema is observed, and an OpenAPI Swagger endpoint is dynamically generated.

### Matrix Authorization Rule
DOORS strictly enforces the **Intersection Access Principle**:
`Target Execution Nodes = (User Authorized Agents) ∩ (Query Authorized Agents)`
- Even if a User is authorized for 10 state agents, if a Query template is only approved for 2 state agents, the query will ONLY execute against those 2 agents.
- Neither the user nor the query alone can target an execution node that has not been mutually authorized.

---

## 4. End-to-End Cryptography & PKI Architecture

### Dual-Layer Cryptographic Envelope
For external API clients and automated consumers:
1. **Inbound Payload Security**:
   - The caller generates a transient 256-bit AES session key.
   - The caller encrypts the payload JSON with `AES/GCM/NoPadding` (128-bit authentication tag, 12-byte IV).
   - The caller encrypts the AES session key using the DOORS Master API RSA Public Key (`RSA/ECB/PKCS1Padding`).
   - The caller signs the plaintext JSON with their Client Private Key (`SHA256withRSA` or `SHA512withRSA`).
   - The envelope `{ encryptedKey, iv, secureData, signature }` is transmitted via POST to `/api/v1/master/gateway/orchestrate/{uniqueName}`.
2. **Master API Inbound Verification**:
   - Master API unwraps the AES session key using its active RSA Private Key.
   - Master API decrypts the ciphertext payload using AES-GCM.
   - Master API validates the digital signature against the client's registered public certificate.
3. **Outbound Response Security**:
   - Master API signs the resulting JSON with its Master Signing Private Key.
   - Master API generates a dynamic AES-256 key, encrypts the response, and wraps the AES key using the client's registered RSA Public Key.
   - Emits headers `X-Content-Secure: true`, `X-DOORS-KID: <key-id>`, and `X-DOORS-TRACE: <trace-id>`.

### Client Public Key Registration & Handshake
- Each API Client must upload `client_public.pem` (X.509 certificate or Base64 RSA public key).
- Before encrypted communication, the client calls `POST /api/v1/master/gateway/handshake` with header `X-API-KEY`.
- DOORS verifies that the presented key fingerprint matches the registered public key.
- Active public signing keys are published at `GET /api/v1/master/gateway/.well-known/jwks.json`.

---

## 5. Governed Document Download Hub
DOORS includes a specialized, high-security Document Download Gateway for retrieving files (e.g. tender notices, technical evaluations, circulars) from regional GePNIC portals:

### Download Flow & Eligibility Evaluation
1. **Manifest Query**: The client calls `POST /api/v1/master/gateway/documents/services/{serviceName}/queries/{queryName}` to discover available files.
2. **Eligibility Verification**: Before serving file bytes, DOORS contacts the regional execution agent to evaluate document access:
   - **`QUERY` Mode**: Executes a secure read-only SQL verification against regional database records.
   - **`FUNCTION` Mode**: Invokes a designated approved PostgreSQL stored procedure (e.g. `is_document_download_eligible(:tender_id, :doc_id)`).
   - If eligibility returns anything other than `ALLOW`, download is immediately blocked.
3. **Streaming Delivery**: If allowed, the file is streamed directly down the HTTP network socket without retaining full-file memory buffers in Master API.
4. **Integrity Validation (SHA-256)**:
   - Master API calculates the source SHA-256 checksum on the fly and returns it in header `X-DOORS-SHA256`.
   - The client SDK writes a temporary `.part` file, computes the destination SHA-256 digest, and renames the file only after a match.
   - The client calls `POST /api/v1/master/gateway/documents/downloads/{correlationId}/receipt` sending `sourceSha256` and `destinationSha256`.
   - Immutable records are preserved in `document_download_audits`.

---

## 6. Password Policy and Multi-Factor Authentication

### Password Composition Rules
DOORS enforces the same password composition policy for user-selected passwords and administrator-issued temporary passwords:
- Minimum 12 characters and maximum 128 characters.
- At least one uppercase letter, one lowercase letter, one number, and one special character.
- Whitespace is not permitted.
- A replacement password must differ from the current password.

### Password Storage and Forced Change
DOORS stores the canonical credential as BCrypt applied to the SHA-256 digest of the password; plaintext passwords are not stored. A successful legacy BCrypt-of-plaintext login is upgraded to the canonical representation. Administrator password reset sets `passwordResetRequired=true`, revokes the current session, and requires the user to replace the temporary password. A successful password change clears the forced-change flag, revokes the current session, expires the session cookie, and requires a fresh sign-in.

### TOTP MFA Controls
Password verification does not create a portal session. Every human login proceeds to a TOTP challenge. DOORS uses six-digit HMAC-SHA-1 codes with a 30-second period and accepts the current time interval plus one interval before or after for clock tolerance. The challenge expires after five minutes and is removed after five failed attempts. First-time enrollment uses a cryptographically random 20-byte Base32 secret and an `otpauth` provisioning URI with issuer `DOORS`.

### MFA Secret Protection and Recovery
TOTP secrets are encrypted using AES-256-GCM with a random 12-byte IV and a 128-bit authentication tag. `DOORS_SECURITY_MFA_ENCRYPTION_KEY` must be Base64 and decode to exactly 32 bytes. A production manager-plane deployment fails startup when the development MFA key remains configured. An authorized MFA reset disables MFA, removes the encrypted secret, revokes the active session, records `MFA_RESET`, and forces enrollment at the next login.

### Authentication and Privileged Access
Login requires a valid CAPTCHA before password verification. Plane, VPN entitlement, and configured IP/CIDR allowlist checks are enforced before a privileged session is issued. Invalid password and MFA attempts create authentication-failure audit events; successful MFA completion creates an authentication-success event. The `DOORS_SESSION` cookie is HttpOnly, Secure when configured, SameSite Strict, limited to eight hours, and cleared at logout or after credential recovery.

## 7. Troubleshooting Runbooks & Error Codes

### Common HTTP Status Codes
- **`400 Bad Request`**: Malformed payload, invalid JSON, or missing required parameter.
- **`401 Unauthorized`**: Authentication missing or expired. Captcha expired, password incorrect, or session cookie missing.
- **`403 Forbidden`**:
  - `DOORS-CSRF-INVALID`: CSRF token stale or missing. Portal automatically refreshes `/api/v1/auth/csrf` and retries.
  - `DOORS-ROLE-PLANE-DENIED`: Attempted to access an admin endpoint from an external plane, or external user logging into admin portal.
  - `DOORS-VPN-ENTITLEMENT-REQUIRED`: Privileged role logging in without an approved NIC VPN connection.
  - `DOORS-IP-NOT-ALLOWED`: User IP does not match the configured allowlist.
- **`404 Not Found`**: Target query template, agent, or document service does not exist or is flagged inactive.
- **`409 Conflict`**:
  - `DOORS-CLIENT-KEY-MISMATCH`: The public key presented in SDK handshake does not match the registered public key in DOORS.
- **`421 Plane Unknown`**: The request HTTP `Host` header does not match any registered DOORS plane (`doorsadmin`, `doorsexternal`, `doorsapi`).
- **`429 Too Many Requests`**: Bucket4j rate limit breached. Interactive UI users: 10 burst clicks/min. Gateway consumers: 5 requests/min.
- **`502 Bad Gateway` / `503 Service Unavailable`**: Regional execution agent offline, or local Ollama AI model taking too long on CPU.

### Cryptographic Triage
- **`RSA BadPaddingException` during Decryption**:
  - Cause: The private key held by the recipient does not match the public key registered in DOORS.
  - Fix: Check the SHA-256 fingerprint in the DOORS UI under **API Clients -> Key Fingerprint** and compare with `openssl rsa -in private_key.pem -pubout -outform DER | openssl dgst -sha256`.
- **`PKIX Path Building Failed` (TLS Handshake)**:
  - Cause: SDK client does not trust the self-signed or internal NIC issuing CA certificate.
  - Fix: Import the server certificate into the client Java truststore via `keytool -importcert -alias doors -keystore cacerts -file cert.pem`. Never use trust-all workarounds in production.
