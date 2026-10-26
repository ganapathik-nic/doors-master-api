# DOORS Operational Workflows

## Read query onboarding

1. Submit the query purpose and parameterised SELECT SQL.
2. Use only `:p_` input parameters and provide representative test values.
3. Run a dry-run against an authorised sandbox Agent.
4. Submit the query for independent review.
5. The reviewer verifies SQL, parameters, returned columns and Agent scope, then approves or rejects it.
6. Authorise the approved query and Agent for the intended API client.
7. Configure credentials, IP allowlist and encryption, then activate the client.
8. Test through Versions / SDKs and review Execution Logs.

## Read-service API-client onboarding

1. Open **API Client Registry → Read Services** and register a new client or select an existing authorised client.
2. Record the generated API key securely. The API key identifies the application but does not by itself grant query access.
3. Configure the client's public certificate and analyse it before saving. The client retains its private PKCS12 identity.
4. Add the permitted source IP addresses or CIDR ranges.
5. Use **Queries** to authorise only the approved read queries required by the application.
6. Configure **Segregation** when the client must be restricted to organisation-, department- or instance-specific rows.
7. Use **Agents** to select the permitted GePNIC databases. Runtime execution uses the intersection of the client's Agents and each query's approved Agents.
8. Enable encryption and activate the client only after credentials, IP policy, queries and Agent scope are complete.
9. Open **Versions / SDKs** to select the supported API version, generate the UAT or Production SDK and review the API guide/OpenAPI contract.
10. Test with the UAT SDK or governed API test, then confirm the request, client, Agent, parameters, result and trace ID in Execution Logs.

Read-service onboarding does not create or modify database tables. It executes only approved parameterised SELECT queries. Read permissions never grant write-service access.

## Write-service onboarding

1. Propose a write service with a title, lowercase service code, purpose and representative JSON.
2. Analyse JSON, review required fields, choose PostgreSQL types and select the record key.
3. Preview tables and rules, then generate the proposal.
4. Run sandbox write and read-back.
5. Submit for independent approval; the proposer cannot approve their own proposal.
6. Download the approved DBA provisioning or migration SQL.
7. The GePNIC DBA runs the SQL in the Agent's database.
8. Map one or more Agents and verify schema and permissions separately against each.
9. In API Client Registry, configure Credentials & Security, add the service mapping and apply service permission.
10. Download the UAT SDK or use Swagger. Production uses the client's own PKCS12 identity and registered public certificate.

## Write record behaviour

The approved record key identifies the logical record. DOORS assigns record versions automatically. A changed JSON document creates the next snapshot. Byte-independent canonical JSON comparison prevents an identical document from creating another version and returns UNCHANGED. A request UUID provides idempotency: repeating the same UUID and identical payload replays the result; using that UUID with changed data returns IDEMPOTENCY_CONFLICT. The generated Java client creates and retains request IDs automatically.

## Troubleshoot a failed API request

1. Capture the HTTP status, response code and DOORS trace ID.
2. Open Execution Logs and search by trace ID, API key, client, endpoint or time.
3. Confirm the API client is active and the source IP is allowed.
4. Confirm the public key and request signature match the client identity.
5. Confirm query/write mapping, Agent and service permission.
6. For Agent failures, inspect Agent health and the corresponding Agent log.
7. For timeouts with unknown write results, retry the exact payload with the same request ID; never reuse the request ID for changed JSON.

## Document-download investigation

1. Ask AIra for today's live document download count or open Document Dashboard.
2. Filter logs by client, service, instance, outcome or trace ID.
3. Distinguish manifest, eligibility, upstream stream and checksum failures.
4. Verify the receipt and destination checksum before declaring delivery successful.

