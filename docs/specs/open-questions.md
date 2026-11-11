# Open questions

Remaining decisions concern freshness, scale and externally unspecified behavior.
M02/M03 decisions below are implementation choices, not changes to OpenAPI.

M01 now applies the provisional policy explicitly requested for application work:
deduplicate similar IDs by first occurrence; fail fast for an absent individual
detail; retain other errors/causes; propagate cancellation. An absent detail becomes
`MissingProductException` with its ID; an empty ID-list publisher violates the port
contract and is not evidence of a missing source. Successful empty lists remain valid.
M02 retains this policy after inspecting the real Docker mocks. Its empty-body
error mapping is: missing detail 404; malformed payload/unexpected HTTP status 502;
transport failure 503; unexpected internal error 500. Additional status codes are
implementation-specific. M03 adds timeout 504 and bounded-pool admission rejection
503, also with empty bodies. See [M02 live evidence](m02-verification.md) and
[M03 policies and verification](m03-resilience-verification.md).

1. **Public 404 semantics (M01/M02):** Is 404 for a missing source only, and how
   is source existence established when `similarids` documents only 200? What
   should an unknown source return? Must errors have an empty body or a JSON body?
   M02 returns empty 404 for a missing detail. Unknown-source lookup returns the
   mock router's generic plain-text 404, mapped to 502 without claiming source
   absence. A source-not-found contract still requires clarification.
2. **Missing similar detail (M01/M02):** Skip the detail, fail the full request,
   or return another agreed outcome? Source 4 includes detail 5 (404) while its
   other details are valid. The contract does not define partial success. M01 uses
   fail-fast provisionally; M03 retains it after live verification. No evidence
   establishes partial success as correct.
3. **Upstream errors (M01/M03):** How do detail 500, lookup errors, malformed
   payloads and network failures map to public statuses? Source 5 includes a
   detail 500. The public spec currently documents only 200 and 404; agree any
   additional error contract without editing supplied challenge files.
   M02 mappings are recorded above; revisit only with an agreed contract change.
4. **Latency versus completeness (M03 decision; M04 measured):**
   No latency SLO is supplied. Engineering defaults are connect 1 s, acquisition
   500 ms, read inactivity 6 s, complete call 7 s and total execution 10 s. The
   50-second fixture fails the whole operation with 504; no omission or retry.
   M04 measured these defaults: normal cold startup and slow cold traffic reject
   some requests; warm runs succeed. A concurrency-1 candidate performed worse
   and was discarded. No new SLO or larger resource limit was agreed.
5. **ID compatibility (resolved in M02):** Non-empty strings stay unchanged;
   exact integral numbers normalize to strings (including decimal/exponent notation
   with integral value). All other types/fractional values fail. Detail identity
   must match the requested ID. No global coercion or identity rewrite occurs.
6. **Uniqueness and scale (M03/M04):** M01 collapses duplicate similar IDs by first
   occurrence. M02 rejects mismatched detail identities.
   OpenAPI `uniqueItems` compares array values, not product identity. What maximum
   list size and runtime concurrency value should bound buffering and upstream
   pressure? Per-request concurrency stays 4 after M04 measurements. The shared
   pool caps active connections at 32 with pending admission policy 64; M04 traced
   a transient pending counter of 87 during concurrent enqueue/drain. This is not
   a strict atomic pending/heap ceiling. Should public admission or dependency
   fairness receive a separate requirement? Codec body limits are retained.
   Maximum contractual list size,
   total public demand and an explicit byte-based memory budget remain open.
7. **Cache freshness (M03 provisional decision):** Successful details only,
   maximum 1,000 entries, 30 s expire-after-write. No absence, error, ID-list or
   stale-on-error caching. Price/availability retain a value for at most this TTL
   after insertion; the contract supplies no freshness SLA or version information.
   Independent concurrent misses can complete in any order: the last valid write
   wins, without proving that its upstream value is newest. Is this freshness
   trade-off acceptable? M04 cold/warm comparison confirms useful caching and
   duplicate-miss pressure; it does not establish an acceptable freshness window.
   Reliable coalescing/cancellation and version ordering need deliberate design
   before changing this policy. No breaker or TTL change was added.
8. **Performance acceptance (M04 measured, targets open):** Which latency
   percentiles, error rate and throughput targets should be used? The official
   supplied k6 run completed but defines neither thresholds nor correctness checks.
   Cold normal had 3.92% non-200 and slow 36.32%; warm diagnostics had none.
   Most verySlow 504s were too early to establish the intended read-timeout path;
   status alone is insufficient. Single-run, emulated-host measurements cannot
   establish an SLO. Preserve the script and agree targets separately.

M00's Docker blocker is resolved in this M02 session: simulado started and live
responses were inspected, including generic unmatched-route 404 and delays near
1/5/50 seconds. Port 5000 remains occupied by macOS Control Center; the successful
runtime smoke test used SERVER_PORT=5001. No real empty-similarities fixture exists;
that valid contract case is tested using a local stub, not presented as a live mock
observation. The benchmark is still deferred to M04.

M03 reverified the unchanged Docker fixtures on port 5001: 5-second details succeed;
the 50-second detail now returns 504 in about 6 seconds. Missing-detail 404, detail
500 -> 502 and generic unknown-source 404 -> 502 remain unchanged. A separate
200 ms total-budget override also terminates slow work with 504. These smoke checks
are correctness evidence, not throughput or percentile measurements. No k6 run yet.

M04 is now executed: [report](m04-performance-verification.md) and compact raw
[evidence](../performance/README.md). The historical statements above describe M02/M03
at their completion; the benchmark is no longer deferred. Port 5000 was released
through supported AirPlay Receiver settings (now disabled); the official target was
unchanged. No production optimization or default change was retained. M05 audits
these accepted limitations in the [delivery audit](../qa/final-delivery-audit.md);
M06 has not started.
