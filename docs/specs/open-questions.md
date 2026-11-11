# Open questions

These remaining integration decisions do not block application-only M01. Resolve
and record them before implementing the affected behavior; design proposals are
not additional requirements.

M01 now applies the provisional policy explicitly requested for application work:
deduplicate similar IDs by first occurrence; fail fast for an absent individual
detail; retain other errors/causes; propagate cancellation. An absent detail becomes
`MissingProductException` with its ID; an empty ID-list publisher violates the port
contract and is not evidence of a missing source. Successful empty lists remain valid.
HTTP mappings and real upstream validation remain for M02/M03.

1. **Public 404 semantics (M01/M02):** Is 404 for a missing source only, and how
   is source existence established when `similarids` documents only 200? What
   should an unknown source return? Must errors have an empty body or a JSON body?
2. **Missing similar detail (M01/M02):** Skip the detail, fail the full request,
   or return another agreed outcome? Source 4 includes detail 5 (404) while its
   other details are valid. The contract does not define partial success. M01 uses
   fail-fast provisionally; revisit only with verified evidence in M02/M03.
3. **Upstream errors (M01/M03):** How do detail 500, lookup errors, malformed
   payloads and network failures map to public statuses? Source 5 includes a
   detail 500. The public spec currently documents only 200 and 404; agree any
   additional error contract without editing supplied challenge files.
4. **Latency versus completeness (M03):** What end-to-end deadline and per-call
   timeouts apply, and should the 50-second detail be omitted or fail the request?
   Are retries permitted, with what total budget? No latency SLO is supplied.
5. **ID compatibility (M02):** May the adapter explicitly normalize integral
   JSON numbers from the supplied mocks to string IDs? Proposed: support strings
   and integral numbers only; reject other JSON types. Avoid global coercion.
6. **Uniqueness and scale (M02/M04):** M01 collapses duplicate similar IDs by first
   occurrence. How should duplicate/mismatched product detail IDs be handled?
   OpenAPI `uniqueItems` compares array values, not product identity. What maximum
   list size and runtime concurrency value should bound buffering and upstream
   pressure? M01 requires a positive constructor-supplied cap without choosing an
   operational default; M02 will configure it and M04 will measure it.
7. **Cache freshness (M03):** What TTL and size are acceptable, especially for
   price and availability? Cache detail only or also similarity lists? May absence
   be cached? Proposed starting point: cache successful details only, bounded
   memory and expiry, then measure cold/warm behavior.
8. **Performance acceptance (M04):** Which latency percentiles, error rate and
   throughput targets should be used? Supplied k6 has neither thresholds nor
   correctness checks. Record agreed targets separately and preserve the script.

Environment blocker: Docker CLI and Compose exist but the daemon is unavailable
in this session. Live mock responses, default unmatched-route status and delay
units cannot be verified yet. Recheck when Docker is running; see
[development notes](../development.md).
