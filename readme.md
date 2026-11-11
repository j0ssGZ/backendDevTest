# Similar Products API

A Java 21 / Spring Boot service for the [backend development challenge](https://github.com/dalogax/backendDevTest).
It fetches ordered similar-product IDs, retrieves their details concurrently and
returns one complete JSON array. The default HTTP port is **5000**.

The supplied [public OpenAPI](similarProducts.yaml), [upstream OpenAPI](existingApis.yaml),
[Docker services](docker-compose.yaml), mocks and k6 script remain unchanged.
See the [delivery audit](docs/qa/final-delivery-audit.md) for compliance and verification.

## Architecture and stack

One Maven module with dependencies directed inward:

- **Domain:** immutable `Product` record, exact `BigDecimal` price and only
  contract-supported invariants; no Spring, Reactor, Jackson or HTTP dependencies.
- **Application:** `GetSimilarProducts` use case and `SimilarProductIds` / `ProductDetails`
  outbound ports. Reactor orchestration deduplicates IDs by first occurrence and uses
  bounded `flatMapSequential` to preserve similarity order despite completion order.
- **Infrastructure:** thin WebFlux controller, explicit public response model,
  WebClient adapter for both upstream operations, error translation and Spring wiring.
  Infrastructure decorators add the overall execution deadline and detail caching.

Java **21**, Spring Boot **3.5.16**, WebFlux/Reactor, Maven Wrapper **3.3.4** with
Maven **3.9.16**, Caffeine **3.2.4**, JUnit **5**, Reactor Test and WebTestClient.
Boot manages dependency versions. Domain/application have no infrastructure imports;
application ports deliberately use Reactor types for cancellation and concurrency.
[Design and trade-offs](docs/specs/design.md) describe the boundaries in detail.

## API contract

```http
GET /product/{productId}/similar
```

Verified against the supplied Docker mock, `/product/1/similar` returns HTTP 200,
`Content-Type: application/json`, and:

```json
[
  {"id":"2","name":"Dress","price":19.99,"availability":true},
  {"id":"3","name":"Blazer","price":29.99,"availability":false},
  {"id":"4","name":"Boots","price":39.99,"availability":true}
]
```

IDs remain strings; only `id`, `name`, `price` and `availability` are returned.
Valid empty similarities produce `[]`. There is no availability filtering or price
rounding. Supplied numeric upstream IDs are normalized only when mathematically
integral; non-empty string IDs are preserved. Invalid JSON/types and mismatching
product identities fail instead of being silently coerced or rewritten.

Mapped endpoint failures have empty bodies:

| Status | Meaning in this implementation |
| --- | --- |
| 404 | An individual similar-product detail is absent; **not proof that the source is absent** |
| 502 | Unexpected upstream status, invalid payload or mismatching identity |
| 503 | Transport failure or rejected pool admission |
| 504 | Connection/acquisition/read/call timeout or overall execution deadline |
| 500 | Unexpected internal failure |

The supplied public spec declares only 200/404 and does not define the 404 trigger.
Additional mappings are implementation decisions. An unknown source's generic
upstream `similarids` 404 maps to **502**; no source-existence probe is invented.
See [open questions](docs/specs/open-questions.md).

## Getting started

Prerequisites: a **Java 21 JDK**, with `JAVA_HOME` pointing to it and its `bin`
directory on `PATH`, and Docker with
Docker Compose v2-compatible `docker compose` support for the supplied services.
The application runs on the host; no system Maven installation is needed. The first
wrapper invocation needs internet access to download Maven and dependencies.

```sh
git clone https://github.com/j0ssGZ/backendDevTest.git
cd backendDevTest
java -version
./mvnw -v
docker compose config --quiet
docker compose up -d simulado
curl --retry 5 --retry-connrefused --retry-delay 1 -f -i http://localhost:3001/product/1/similarids
./mvnw clean verify
java -jar target/similar-products-api-0.0.1-SNAPSHOT.jar
```

Keep the application running, then in another terminal:

```sh
curl -i http://localhost:5000/product/1/similar
```

Alternatively start it with `./mvnw spring-boot:run`. Docker is required for the
supplied mocks/benchmark, not for the automated tests. Docker Desktop may require
file-sharing access to the checkout's `shared` directory. Compose's obsolete
`version` warning is inherited and does not require changing the supplied file.

If 5000 is occupied, identify its listener first. For local smoke checks only:

```sh
SERVER_PORT=5001 java -jar target/similar-products-api-0.0.1-SNAPSHOT.jar
```

With that process running, use another terminal:

```sh
curl -i http://localhost:5001/product/1/similar
```

The official benchmark still requires 5000. On macOS, AirPlay Receiver can occupy
that port; use the supported System Settings switch if appropriate for your machine.
Stop the foreground application with Ctrl-C, then `docker compose stop` to stop
local services, or `docker compose down` to remove this Compose project's containers
and network. Do not use `down -v` unless you intend to remove its data volumes.

## Configuration

Set environment variables for the application process, or use Spring properties
such as `--resilience.request-timeout=10s`. All defaults below match the code.
Duration suffixes include `ms` and `s`. Pool/concurrency/capacity and durations must
be positive; resilience durations must be at least 1ms.

| Environment variable | Spring property | Default |
| --- | --- | --- |
| `SERVER_PORT` | `server.port` | `5000` |
| `UPSTREAM_BASE_URL` | `upstream.base-url` | `http://localhost:3001` |
| `UPSTREAM_MAX_CONCURRENCY` | `upstream.max-concurrency` | `4` |
| `RESILIENCE_CONNECTTIMEOUT` | `resilience.connect-timeout` | `1s` |
| `RESILIENCE_ACQUIRETIMEOUT` | `resilience.acquire-timeout` | `500ms` |
| `RESILIENCE_RESPONSETIMEOUT` | `resilience.response-timeout` | `6s` |
| `RESILIENCE_CALLTIMEOUT` | `resilience.call-timeout` | `7s` |
| `RESILIENCE_REQUESTTIMEOUT` | `resilience.request-timeout` | `10s` |
| `RESILIENCE_MAXCONNECTIONS` | `resilience.max-connections` | `32` |
| `RESILIENCE_MAXPENDINGACQUISITIONS` | `resilience.max-pending-acquisitions` | `64` |
| `RESILIENCE_MAXIDLETIME` | `resilience.max-idle-time` | `30s` |
| `RESILIENCE_EVICTIONINTERVAL` | `resilience.eviction-interval` | `15s` |
| `PRODUCTCACHE_ENABLED` | `product-cache.enabled` | `true` |
| `PRODUCTCACHE_MAXENTRIES` | `product-cache.max-entries` | `1000` |
| `PRODUCTCACHE_TTL` | `product-cache.ttl` | `30s` |

The upstream URL must be absolute HTTP(S), with a host and no query/fragment.
The existing `UPSTREAM_*` aliases come from YAML; the other environment names follow
Spring binding, removing hyphens within property elements. For example:

```sh
UPSTREAM_BASE_URL=http://localhost:3001 PRODUCTCACHE_ENABLED=false \
  java -jar target/similar-products-api-0.0.1-SNAPSHOT.jar
```

## Testing

`./mvnw clean verify` passes **166 tests** (including parameterized cases):

- Plain domain/application tests cover invariants, decimals, ordering, duplicates,
  bounded concurrency, fail-fast behavior and cancellation without a Spring context.
- Controller, HTTP-adapter and Spring integration tests exercise JSON types/fields,
  encoded paths, normalization, identity checks and sanitized error responses.
- Virtual time, controlled publishers and fake cache time test deadlines and expiry;
  short loopback Netty tests verify real response/acquisition timeouts and pool cleanup.

Tests use deterministic local HTTP stubs, require neither Docker nor external APIs,
and use random server ports. Contract assertions check the supplied schema's behavior;
there is no generated OpenAPI-validator conformance certification. A separate live
Docker smoke check and fresh-cache build are recorded in the [audit](docs/qa/final-delivery-audit.md).

## Continuous integration

[Java CI](.github/workflows/ci.yml) runs on pushes to `main`, pull requests targeting
`main`, and manual dispatch from the [Actions page](https://github.com/j0ssGZ/backendDevTest/actions/workflows/ci.yml).
It uses `ubuntu-latest`, Temurin Java 21 and the Maven Wrapper (Maven 3.9.16), with
Maven dependency caching. Cache misses still resolve dependencies normally; build
outputs are not cached. The local equivalent is:

```sh
./mvnw -B -ntp clean verify
```

This compiles, runs all unit and HTTP/Spring integration tests, and packages the JAR.
Tests use isolated loopback stubs and ephemeral ports; Docker, live mocks, port 5000
and k6 are not required or run in CI. The job has a 15-minute limit. Superseded runs
for the same branch/PR are canceled. Official actions are pinned to full commit SHAs,
permissions are `contents: read`, and checkout credentials are not persisted.

When Maven fails, existing Surefire XML/text reports are uploaded for seven days;
no JARs, settings files or environment dumps are uploaded. Setup/compilation failures
may have no test reports; their errors remain in the job log. No failure is suppressed.

Dependency/image vulnerability scanning is **not included**; the M05 unverified
security finding remains open. No credential-dependent scanner, external database
update gate, deployment or publishing was added. GitHub/Maven/JDK downloads still
require network access; `ubuntu-latest` and Java 21 patch versions can evolve.
The workflow is implemented, with its first hosted verification **pending**; a local
passing build does not establish green GitHub CI. See [M06 status](docs/specs/tasks.md).

## Performance testing

Run the application on host port **5000**, then:

```sh
docker compose up -d simulado influxdb grafana
docker compose run --rm k6 run scripts/test.js
```

The unmodified script is `shared/k6/test.js`, mounted as `/scripts/test.js`; it targets
`http://host.docker.internal:5000`. Docker must be able to reach the host application.
Compose supplies a `host-gateway` mapping for Linux; firewalls/host networking may
still need local attention. Grafana results are available at
[the supplied dashboard](http://localhost:3000/d/Le2Ku9NMk/k6-performance-test).

M04 executed the official benchmark: 17,022 responses, with cold-cache rejection and
shared-pool interference documented. It has five sequential scenario windows, up to
200 VUs at a time, no correctness assertions and no performance thresholds. Exit 0
means the run completed, **not** that a latency/error SLO passed. Intentional 404/502
and timeout 504 responses must be distinguished from saturation failures.
See the [M04 report](docs/specs/m04-performance-verification.md) and
[compact raw evidence and analysis tools](docs/performance/README.md).

## Resilience, caching and limitations

Fail fast for missing or failed details; collect the complete list before success.
No fabricated product, omitted failing detail or successful empty fallback is returned.
There are no automatic retries, including Netty's implicit reset retry. Call budgets
include acquisition and body consumption; one outer 10s execution deadline covers
the complete use case without resetting per detail. Cancellation reaches pending
work. This is an execution budget, not a hard network-delivery/serialization SLO.

A dedicated pool limits active connections to 32 with a 64-pending admission policy.
M04 observed the internal pending counter transiently reach **87** during concurrent
enqueue/drain; 64 is **not a strict atomic counter ceiling**. The pool is disposed at
shutdown. It does not provide public-request admission limits or per-route fairness.

Caffeine caches only successful, identity-validated immutable details, up to 1,000
entries for 30s after insertion. Hits do not extend expiry. Failures, missing products
and similarity-ID lists are not cached. Independent concurrent misses can duplicate
loads; no in-flight coalescing is implemented. Cache entries are invalidated at shutdown.

Accepted limitations:

- Cold requests under 200-VU bursts can be rejected; slow misses can also affect
  unrelated fast requests sharing the pool. Latency remains upstream dependent.
- A missing detail's public 404 can be mistaken for source absence; source existence
  and additional error statuses remain underspecified by the supplied contract.
- Price/availability can retain a cached value for 30s. Upstream freshness/version
  ordering is unknown; concurrent successful loads use last completed write.
- The final result buffers in memory; the contract specifies no maximum list size.
  Cache capacity counts entries, not bytes. No strict whole-process memory bound is claimed.
- Performance evidence is a single-machine snapshot, with supplied k6/simulado
  amd64 images emulated on ARM. It establishes no portable throughput or latency guarantee.

## Deliberate scope

No database, Redis or distributed cache is needed for this read-only upstream
aggregation challenge. A circuit breaker was not added without a demonstrated
benefit beyond existing bounds; arbitrary retries would amplify failure traffic.
Partial fabricated responses would violate the chosen completeness policy.
Deployment and authentication are outside this challenge implementation. GitHub CI
is configured as described above; M06 completion awaits successful hosted verification.
The [milestone plan](docs/specs/tasks.md) and
[final audit](docs/qa/final-delivery-audit.md) record delivery scope and outstanding decisions.
