# M03 resilience decisions and verification

## Policy selected before implementation

These are engineering defaults, not challenge-provided SLOs. Preserve M02 fail-fast
behavior, exact JSON, ordered products and the unresolved source-404 ambiguity.

| Condition | Public behavior | Cleanup / cache behavior |
| --- | --- | --- |
| Similar-ID lookup failure | Existing HTTP/payload/transport mapping; no successful empty fallback | Cancel active operation; never cache ID lists |
| Missing individual detail | 404, empty body; no claim that source is absent | Cancel other lookups; do not cache absence |
| Upstream HTTP 500 or other unexpected status | 502, empty body | Release response; no failed entry |
| Connection failure | 503, empty body | Release failed acquisition; preserve transport cause |
| Connection acquisition failure | Full queue: 503; acquisition timeout: 504 | Remove pending acquisition; no indefinite wait |
| Per-call timeout (including TCP/response) | 504, empty body, distinct from missing detail | Cancel HTTP subscription and release/discard channel |
| Overall execution deadline | 504, empty body; no partial products | Cancel ID/detail work and queued lookups; deadline never resets per detail |
| Malformed/invalid upstream response | 502, empty body | No malformed product admitted to cache |
| Local overload | 503, empty body on bounded pool admission rejection | No additional application queue or retry |
| Cached detail | Use valid immutable value within finite TTL | No HTTP connection required; existing similarity order preserved |
| Cache miss | Load normally within the same deadlines | Store only a successful, identity-matching product; errors/absence/cancellation are not entries |

All error bodies remain empty; causes are kept internally. No omission, fabricated
product, stale-on-error response, source-existence probe or automatic retry is added.
The generic similar-ID lookup 404 remains 502; missing detail remains 404.

## Selected defaults

- TCP connect: **1 s**; pool acquisition: **500 ms**.
- HTTP response read inactivity: **6 s**; complete outbound call: **7 s**.
- Whole use-case execution: **10 s**, once per subscription around the complete
  ID lookup, ordered detail fan-out, acquisition and upstream error-body handling.
- Dedicated pool: **32** active connections, **64** pending acquisitions per
  upstream origin; maximum idle **30 s**, background eviction every **15 s**.
- Application detail concurrency remains **4 per request**.
- Successful detail cache: maximum **1,000** entries, expire-after-write **30 s**.

Netty response timeout bounds read inactivity, not total acquisition/connect/body
time. The complete-call deadline covers those gaps, and the outer execution budget
also bounds a long sequence of otherwise individually acceptable calls. TCP connect
time is a separate network-stage bound. A 5-second detail may succeed; a 50-second
detail must fail in bounded time. These defaults await M04 measurements.

## Design choices

Use a managed, dedicated ConnectionProvider rather than adding an application
semaphore or another waiting queue. It protects this fixed upstream globally across
requests. Pending admission is finite; excess work fails promptly. Pool eviction and
connections are disposed at Spring shutdown, sharing existing Netty event loops.

Caffeine is selected for concurrent bounded eviction and expiry rather than a
hand-written LRU, expiry map or Mono cache. Decorate only ProductDetails; the domain,
application and port contracts remain unchanged. No similarity-list cache is added.
Maintenance uses a direct executor and lazy expiry: no cache scheduler/thread is
created. Expired values cannot be served; physical cleanup also happens during
bounded cache maintenance. Maximum entries is an eviction policy, not a byte budget.

Caching adds up to the configured 30-second retention after insertion for price
and availability; this cannot establish the freshness of the upstream value itself.
Hits do not extend expiry. Concurrent misses may return different versions and the
last valid completed write wins; the contract supplies no version ordering. No business requirement guarantees this
window: it is an explicit provisional trade-off. Values are immutable and preserve
BigDecimal precision. A completed valid individual detail can remain cached even
when another detail makes its containing public request fail.

Concurrent misses intentionally load independently. Avoiding in-flight coalescing
keeps cancellation local: one caller cannot cancel another, and abandoning the last
caller cannot leave a shared load running. Pool limits bound simultaneous loads.
No map of in-flight Monos, error cache or orphaned cache subscription is introduced.

A circuit breaker is deferred: bounded transport, deadlines and positive caching
address the demonstrated slow/failing fixtures without state-machine complexity.
Persistent-outage traffic reduction may justify it later with evidence; ordinary
missing details must not be treated as dependency health failures. No Resilience4j.

## Framework baseline and implementation changes

M02 already had per-request concurrency 4, cancellation, response-body release,
shared Reactor Netty event loops and bounded codec buffering. Its default global
HttpResources/TcpResources pool was not unbounded: in the managed Reactor Netty
1.2.18 sources it uses 500 active connections, default pending count twice that,
and acquisition timeout 45 s. No maximum idle time was configured. M03 replaces
this with an owned, smaller bounded provider; it does not layer another limiter.
Netty's implicit retry-once for some reset failures is explicitly disabled. M02
had no configured response timeout, complete-call timeout or overall deadline.

Unexpected HTTP response bodies are now read explicitly within the call budget.
Spring's `createException()` can suppress error-body read failures; retaining that
behavior would hide a timeout behind an ordinary upstream status. Tests for both
200 and 500 held bodies prove a timeout remains distinguishable. Original causes
stay internal; the public response contains no exception details.

Pool exhaustion classification is isolated in the HTTP adapter. Reactor Netty
shades its internal pool exception classes; pending-limit/shutdown classification
uses their simple names rather than importing private packages. Real pool tests
protect this version-dependent integration. Acquisition timeout retains its
TimeoutException cause and maps to 504; immediate full-queue rejection maps to 503.

## Runtime configuration

Defaults are declared once in typed `@ConfigurationProperties` records. Invalid
nonpositive durations/capacities fail startup; TCP milliseconds must fit Netty's
integer setting. Override with normal Spring command-line/property sources or
these environment names (hyphens are removed inside property elements):

| Property | Default | Environment override |
| --- | --- | --- |
| resilience.connect-timeout | 1s | RESILIENCE_CONNECTTIMEOUT |
| resilience.acquire-timeout | 500ms | RESILIENCE_ACQUIRETIMEOUT |
| resilience.response-timeout | 6s | RESILIENCE_RESPONSETIMEOUT |
| resilience.call-timeout | 7s | RESILIENCE_CALLTIMEOUT |
| resilience.request-timeout | 10s | RESILIENCE_REQUESTTIMEOUT |
| resilience.max-connections | 32 | RESILIENCE_MAXCONNECTIONS |
| resilience.max-pending-acquisitions | 64 | RESILIENCE_MAXPENDINGACQUISITIONS |
| resilience.max-idle-time | 30s | RESILIENCE_MAXIDLETIME |
| resilience.eviction-interval | 15s | RESILIENCE_EVICTIONINTERVAL |
| product-cache.enabled | true | PRODUCTCACHE_ENABLED |
| product-cache.max-entries | 1000 | PRODUCTCACHE_MAXENTRIES |
| product-cache.ttl | 30s | PRODUCTCACHE_TTL |

Existing `UPSTREAM_BASE_URL`, `UPSTREAM_MAX_CONCURRENCY` and default port 5000
are unchanged. Example local runtime:

```sh
SERVER_PORT=5001 UPSTREAM_BASE_URL=http://localhost:3001 \
  java -jar target/similar-products-api-0.0.1-SNAPSHOT.jar
```

For a shorter execution budget add `RESILIENCE_REQUESTTIMEOUT=200ms`, or use
`--resilience.request-timeout=200ms`. Cache disablement affects only the decorator;
ID lookups always go upstream. There are no retry or circuit-breaker settings.

## Automated verification

Baseline `./mvnw clean verify`: **121 tests, zero failures/errors/skips**.
Final clean verification: **166 tests, zero failures/errors/skips**. All original
121 cases remain unchanged. New cases:

- CachingProductDetailsTests: 10 — lazy miss/hit, exact values, fake-ticker TTL,
  capacity eviction, uncached failure/absence/identity mismatch, overlapping misses,
  independent and abandoned-load cancellation, stable ordering and uncached ID lists.
- DeadlineGetSimilarProductsTests: 5 — virtual-time aggregate deadline across
  initial IDs and serial details, active/queued cancellation, no partial result,
  initial-lookup cancellation, normal success and preserved missing-detail error.
- CallTimeoutTests: 6 — virtual-time whole-call and body deadlines, caller
  cancellation, ordinary success and wrapped TCP-connect timeout classification.
- NettyResourceLimitsTests: 4 — real response read timeouts for 200/500 bodies,
  pending-acquisition timeout, full-queue rejection, cancellation cleanup and reuse.
- ResilienceConfigurationTests: 15 — invalid settings, environment overrides,
  actual wiring, cache disablement, shutdown disposal, malformed-load recovery,
  configured Netty limits and disabled implicit retries.
- ResilienceErrorMappingTests: 5 — timeout/overload and preserved earlier mappings,
  with empty bodies and no internal details disclosed.

Virtual time, controlled publishers and fake cache time cover logical deadlines
and expiry. Short loopback HTTP tests are restricted to actual Netty behavior;
queue-admission signals/latches avoid guessing scheduling order. Finite waits are
failure guards; no test sleeps. Automated tests require neither Docker nor external
HTTP services. No production block/subscribe, manual executor or retry loop is added.
Domain/application source and interfaces are unchanged.

Commands used a process-local Java 21.0.13 installation and Maven cache under
`/tmp/similar-products-m00-tools`; global settings were not modified:

```sh
./mvnw -B -ntp -Dmaven.repo.local=/tmp/similar-products-m00-tools/repository clean verify
```

Build evidence: `/tmp/similar-products-m03/verify.log`. Caffeine **3.2.4** is the
only new dependency, with its version managed by Boot 3.5.16. Its concurrent bounded
size/expiry policies avoid a custom eviction implementation; no Resilience4j,
Redis, database or new test library is added. See the official
[Caffeine eviction documentation](https://github.com/ben-manes/caffeine/wiki/Eviction).

## Actual Docker verification

`docker compose up -d simulado` succeeded with Docker 29.7.2. Supplied Compose,
OpenAPI, mock and benchmark files were not changed. macOS Control Center occupies
5000, so the packaged production JAR ran on 5001. Requests used curl with a finite
12-second guard. Observed on 2026-11-11, using defaults unless stated:

| Request | HTTP | Observed seconds | Body / content type |
| --- | --- | --- | --- |
| Public /product/1/similar | 200 | 0.226368 | Ordered IDs 2,3,4; application/json |
| Same public request again | 200 | 0.007770 | Byte-identical JSON |
| Raw upstream /product/100 | 200 | 1.018534 | Trousers; application/json; charset=utf-8 |
| Public /product/2/similar | 200 | 5.032548 | Ordered IDs 3,100,1000; application/json |
| Public /product/3/similar (50-second detail) | 504 | 6.029537 | Empty, Content-Length 0, no content type |
| Public /product/4/similar (missing detail) | 404 | 0.012983 | Empty, no content type |
| Public /product/5/similar (upstream 500) | 502 | 0.011273 | Empty, no content type |
| Public /product/unknown/similar | 502 | 0.004602 | Empty, no content type |
| Cold /product/2/similar, separate process with 200ms total budget | 504 | 0.267035 | Empty, no partial products |

Representative real response, `GET /product/1/similar`:

```json
[{"id":"2","name":"Dress","price":19.99,"availability":true},{"id":"3","name":"Blazer","price":29.99,"availability":false},{"id":"4","name":"Boots","price":39.99,"availability":true}]
```

The 50-second scenario was exercised after warming its other details; the read
limit cancels its slow detail in about 6 seconds. The separate cold 200ms override
proves the overall budget is configurable and active independently of per-call
limits. Wall-clock measurements include dispatch/startup overhead and are smoke
evidence, not latency SLOs or a benchmark. Repeated-request timing alone does not
prove cache hits: controlled-port invocation-count tests establish that behavior.

Detailed curl headers/bodies, startup logs and JSON observations are under
`/tmp/similar-products-m03/` (local diagnostic artifacts, not repository fixtures).
Both test application processes were stopped; simulado remains available. No
50-second uncontrolled public wait, supplied fixture changes or k6 run occurred.

## Remaining M04 concerns

Measure cold/warm demand, error rates and pool saturation before tuning. The pool
bounds outbound active/pending work, not total accepted public connections or all
request memory. Existing codec caps constrain upstream bodies, but the contract
has no maximum list size or byte-based cache budget. Cache capacity is an entry
policy, not a strict heap reservation. Independent concurrent misses may duplicate
loads; coalescing remains deferred until evidence justifies reliable cancellation
and eviction semantics. Price/availability freshness and source-not-found semantics
remain business ambiguities. The 5-second fixture fits current defaults but no
percentile/throughput target is established. M04 owns the original k6 execution;
M03 does not claim performance optimization or benchmark completion.
