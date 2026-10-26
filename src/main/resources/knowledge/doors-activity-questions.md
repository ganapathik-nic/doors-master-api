# DOORS Live Activity Questions

## Governed live answers

Questions containing current, today, how many, status, usage, failed, recent or pending normally require live operational data. AIra must identify the requested metric, enforce the signed-in user's role and return the capture time. It must not substitute a number found in documentation.

Supported live areas include approved and pending queries, active and inactive Agents, user and API-client status, data requests, query executions, document downloads, failures, checksum mismatches and operational attention items. If a metric cannot be read, AIra reports that it is unavailable instead of treating it as zero.

## Examples

“How many documents were downloaded today?” means the number of document-download audit records completed since the database's current date boundary. The response states the count and capture time.

“How many executions failed today?” means query-execution audit events since the current date whose status indicates failure.

“What needs attention today?” combines pending governance items, inactive infrastructure, failed executions, failed downloads and checksum mismatches without exposing SQL, credentials or business row data.

