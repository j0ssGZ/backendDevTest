# Open questions

Remaining decisions concern resilience and externally unspecified behavior. M02
decisions below are explicit implementation choices, not changes to OpenAPI.

M01 now applies the provisional policy explicitly requested for application work:
deduplicate similar IDs by first occurrence; fail fast for an absent individual
detail; retain other errors/causes; propagate cancellation. An absent detail becomes
`MissingProductException` with its ID; an empty ID-list publisher violates the port
contract and is not evidence of a missing source. Successful empty lists remain valid.
M02 retains this policy after inspecting the real Docker mocks. Its empty-body
error mapping is: missing detail 404; malformed payload/unexpected HTTP status 502;
transport failure 503; unexpected internal error 500. Additional status codes are
implementation-specific. See [live evidence](m02-verification.md).

1. **Public 404 semantics (M01/M02):** Is 404 for a missing source only, and how
   is source existence established when `similarids` documents only 200? What
   should an unknown source return? Must errors have an empty body or a JSON body?
   M02 returns empty 404 for a missing detail. Unknown-source lookup returns the
   mock router's generic plain-text 404, mapped to 502 without claiming source
   absence. A source-not-found contract still requires clarification.
2. **Missing similar detail (M01/M02):** Skip the detail, fail the full request,
   or return another agreed outcome? Source 4 includes detail 5 (404) while its
   other details are valid. The contract does not define partial success. M01 uses
   fail-fast provisionally; revisit only with verified evidence in M02/M03.
3. **Upstream errors (M01/M03):** How do detail 500, lookup errors, malformed
   payloads and network failures map to public statuses? Source 5 includes a
   detail 500. The public spec currently documents only 200 and 404; agree any
   additional error contract without editing supplied challenge files.
   M02 mappings are recorded above; revisit only with an agreed contract change.
4. **Latency versus completeness (M03):** What end-to-end deadline and per-call
   timeouts apply, and should the 50-second detail be omitted or fail the request?
   Are retries permitted, with what total budget? No latency SLO is supplied.
5. **ID compatibility (resolved in M02):** Non-empty strings stay unchanged;
   exact integral numbers normalize to strings (including decimal/exponent notation
   with integral value). All other types/fractional values fail. Detail identity
   must match the requested ID. No global coercion or identity rewrite occurs.
6. **Uniqueness and scale (M03/M04):** M01 collapses duplicate similar IDs by first
   occurrence. M02 rejects mismatched detail identities.
   OpenAPI `uniqueItems` compares array values, not product identity. What maximum
   list size and runtime concurrency value should bound buffering and upstream
   pressure? M02 configures a positive per-request cap, default 4; M04 must measure
   its operating value. Maximum list/body size and global resource budgets remain open.
7. **Cache freshness (M03):** What TTL and size are acceptable, especially for
   price and availability? Cache detail only or also similarity lists? May absence
   be cached? Proposed starting point: cache successful details only, bounded
   memory and expiry, then measure cold/warm behavior.
8. **Performance acceptance (M04):** Which latency percentiles, error rate and
   throughput targets should be used? Supplied k6 has neither thresholds nor
   correctness checks. Record agreed targets separately and preserve the script.

M00's Docker blocker is resolved in this M02 session: simulado started and live
responses were inspected, including generic unmatched-route 404 and delays near
1/5/50 seconds. Port 5000 remains occupied by macOS Control Center; the successful
runtime smoke test used SERVER_PORT=5001. No real empty-similarities fixture exists;
that valid contract case is tested using a local stub, not presented as a live mock
observation. The benchmark is still deferred to M04.
