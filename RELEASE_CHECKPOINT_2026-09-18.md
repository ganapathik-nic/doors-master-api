# Release checkpoint — 18 September 2026

## Included changes
UI security and SQL-entry fixes; gateway V2 and SDK generation; API subscriptions, accounting periods and weekly/date-specific access; role dashboards; visual API manual; Documentation Hub walkthrough player and optional browser voice narration; supporting tests and DAST reports.

## Database migrations
The Master API repository includes six new versioned migrations: V2026091401, V2026091402, V2026091701, V2026091702, V2026091703 and V2026091704. Previously applied SQL files were preserved. Flyway read-only validation against the local database passed for all 32 migrations; latest installed version 2026091704; no pending migrations. The original subscription migration checksum remains 1757144984. Migration and database tests used temporary tables and rollback to verify accounting/service preservation.

Deploy the matching Master API and UI revisions together. Back up the target database before an upgrade. Allow the application's configured Flyway startup migration to apply pending scripts; never edit an already-applied migration or use repair to suppress checksum mismatches. The user-to-license upgrade intentionally refuses to discard existing client-license data that needs reconciliation.

## Verification
- 117 selected backend tests passed, including installed Flyway validation and real PostgreSQL temporary-table migration tests.
- 54 UI tests passed with one worker; the first concurrent attempt timed out starting workers.
- Current UI type check and production build passed during walkthrough narration deployment.
- Portal walkthrough and voice sequencing browser tests passed with synthetic session/API data and a simulated speech engine. Audible voice quality remains browser-dependent.

## Repository hygiene
Source, curated screenshots/manuals, reusable guide build scripts and tests are included. Runtime exports, financial snapshots, secrets, caches, local deployment copies and temporary outputs remain local. Previously partially tracked dist output is removed from version control only; local files remain. Build the UI from source before deployment. Aira's local vector cache remains local and is rebuilt from the packaged manuals using the existing indexing procedure.
