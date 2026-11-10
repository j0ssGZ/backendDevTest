# Open questions

These gaps do not block M00. Resolve and record decisions before implementing
the affected behavior; proposals in the design are not additional requirements.

1. **Public 404 semantics (M01/M02):** Is 404 for a missing source only, and how
   is source existence established when `similarids` documents only 200? What
   should an unknown source return? Must errors have an empty body or a JSON body?
2. **Missing similar detail (M01/M02):** Skip the detail, fail the full request,
   or return another agreed outcome? Source 4 includes detail 5 (404) while its
   other details are valid. The contract does not define partial success.
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
6. **Uniqueness and scale (M01/M04):** Should duplicate IDs be collapsed by first
   occurrence? How should duplicate/mismatched product detail IDs be handled?
   OpenAPI `uniqueItems` compares array values, not product identity. What maximum
   list size and concurrency cap should bound buffering and upstream pressure?
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
