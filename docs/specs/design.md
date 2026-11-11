# Architecture plan

## Foundation decision

Use one Maven module, Java 21 and Spring Boot **3.5.16**. This is a stable 3.5
release compatible with Java 21 and Maven >= 3.6.3, and manages JUnit Jupiter
5.12.2, Spring Framework 6.2.19 and Reactor BOM 2024.0.18. The newer stable Boot
4.1.2 uses JUnit 6; the 3.5 line meets the requested JUnit 5 baseline without
overriding the managed test platform. Maven Wrapper pins Maven 3.9.16.
Do not individually pin Spring, Reactor, Jackson or JUnit dependencies.

Compatibility checked before implementation against the
[Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[published dependency BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom)
and [Initializr metadata](https://start.spring.io/metadata/client).
Only WebFlux and the test starter are direct application dependencies in M00.

## Boundaries

- **Domain:** immutable `Product` with string ID, name, `BigDecimal` price and
  boolean availability. Enforce required values and minimum string length;
  do not invent positivity, currency or whitespace rules. Keep Spring,
  Reactor, HTTP and Jackson annotations outside this layer.
- **Application:** `GetSimilarProducts` inbound interface and service orchestrate
  two outbound ports: `SimilarProductIds` and `ProductDetails`. Reactive port
  signatures may use Reactor (`Mono<List<String>>`, `Mono<Product>` and
  `Mono<List<Product>>` for the use case). This deliberately couples orchestration
  to Reactor to keep cancellation, concurrency and backpressure straightforward,
  while leaving domain values framework free.
- **Infrastructure:** a WebFlux controller maps the public contract; WebClient
  adapters translate upstream JSON/statuses into domain values and application
  outcomes. Spring configuration wires concrete adapters and the service.
  The application owns port interfaces; adapters implement them.

Dependency direction: infrastructure -> application -> domain. Infrastructure
may use domain values. Application must not import WebClient, controller DTOs
or Spring configuration. Keep one product HTTP adapter implementing both outbound
ports initially; split it only if responsibilities actually diverge. Use explicit
boundary DTOs where needed to isolate JSON coercion and error semantics.

## Package layout

M00 created the entry point, YAML and context test; M01 implemented the domain
and application. M02 implements the infrastructure below without changing either
inner layer or its port interfaces.

```text
src/main/java/com/example/similarproducts/
  SimilarProductsApplication.java
  domain/Product.java
  application/
    GetSimilarProducts.java
    GetSimilarProductsService.java
    MissingProductException.java
    port/out/SimilarProductIds.java
    port/out/ProductDetails.java
  infrastructure/
    rest/SimilarProductsController.java
    rest/ProductResponse.java
    rest/ApiExceptionHandler.java
    http/ProductApiClient.java
    http/UpstreamPayloadDecoder.java
    http/UpstreamException.java
    config/UpstreamProperties.java
    config/ProductConfiguration.java
src/main/resources/application.yaml
src/test/java/com/example/similarproducts/
  SimilarProductsApplicationTests.java
  domain/...
  application/...
  infrastructure/...
```

No empty packages, placeholder implementations, multi-module build or generic
repository abstraction is needed. The upstream URL is already configurable via
`UPSTREAM_BASE_URL` (default `http://localhost:3001`) or `upstream.base-url`.
M02 supplies typed properties and HTTP client wiring in infrastructure.

## M02 REST and upstream boundaries

The thin controller delegates `GET /product/{productId}/similar` to the use case
and maps Product to ProductResponse. The explicit response record has only `id`,
`name`, `price`, `availability`; ordering and orchestration remain in application.

One ProductApiClient implements both outbound ports with WebClient. It requests
`/product/{productId}/similarids` and `/product/{productId}` using URI-template
variables and explicit `TEMPLATE_AND_VALUES` encoding, preserving IDs as single
segments (including slashes, spaces, Unicode and literal percent signs). Configured
base-path prefixes are supported. Requests advertise JSON. `exchangeToMono` handles
status-specific decoding and releases bodies; only detail 404 completes empty.

Successful upstream responses must be 200 with `application/json` or an application
media type ending in `+json`, optionally with charset parameters. Empty bodies are
invalid. A local immutable Jackson ObjectReader checks the JSON tree, rejects
duplicate object keys/trailing root values and reads decimals as BigDecimal without
scale conversion. No global Jackson coercion settings change. Explicit tree checks
replace the originally planned upstream DTO: JSON types and required-field presence
must be checked before constructing a Product, especially missing availability versus
false. Unknown upstream fields are ignored and cannot enter the public response.

ID strings are non-empty and preserved verbatim; mathematically integral numbers
are converted with `toBigIntegerExact()` (including `2.0`/`2e0`, without lossy double
conversion). Fractional numbers and other JSON types fail. The normalized detail ID
must exactly equal the requested string ID; `2` does not silently replace `"002"`.

UpstreamException retains kind, upstream status when present, and original transport,
HTTP or parser cause. Application passes it through unchanged. The REST advice emits
empty error bodies: missing similar detail -> 404; invalid payload/identity or
unexpected upstream status -> 502; transport failure -> 503; unanticipated internal
failure -> 500. Framework status exceptions retain their status without exposing
their messages. The generic unknown-route 404 from `similarids` is a 502 dependency
failure, not evidence for a new source-not-found rule. See the verification record.

## Runtime configuration

- `server.port`: default **5000**; `SERVER_PORT=5001` is a temporary local override.
- `upstream.base-url`: `UPSTREAM_BASE_URL`, default `http://localhost:3001`.
  Typed URI must be an absolute HTTP(S) base URL with host and no query/fragment.
- `upstream.max-concurrency`: `UPSTREAM_MAX_CONCURRENCY`, default **4**, must be
  positive. Four is a conservative starting cap per request, allowing the supplied
  three-detail examples to overlap without unbounded fan-out. It is not a benchmark
  result, a global concurrency limit, or a latency guarantee; tune in M04.

Spring ProductConfiguration constructs the HTTP adapter and injects it into both
application ports, then constructs the use case with the configured cap. No Spring
annotations or infrastructure types enter the domain/application. No new dependency
is needed for M02; loopback test servers use the existing Reactor Netty dependency.

## M01 port contracts

- `GetSimilarProducts.getSimilarProducts(String) -> Mono<List<Product>>`: lazy,
  one complete ordered list per successful subscription, including an empty list.
- `SimilarProductIds.findSimilarIds(String) -> Mono<List<String>>`: one ordered
  list of non-null string IDs or an error. Empty list is valid; `Mono.empty()` is
  invalid and becomes an `IllegalStateException`, never a source-not-found outcome.
- `ProductDetails.findById(String) -> Mono<Product>`: emit the requested product,
  complete empty for absence, or emit an error. Empty completion becomes
  `MissingProductException(productId)` in the service. Other errors retain their
  original identity, type and cause, including errors thrown before returning a Mono.
- Ports must return non-null publishers and support cancellation. No HTTP types or
  statuses appear in these contracts. The service is constructed directly with
  both ports and a positive `maxConcurrency`; M02 supplies Spring wiring externally.

`Product` is a Java record. ID, name and price are non-null; ID and name are
non-empty, with no trimming/nonblank restriction. `BigDecimal` is retained without
rounding or scale conversion; zero and negative values remain legal. Availability
is a primitive boolean. M02's HTTP decoder validates JSON required-field/null
presence, including distinguishing missing availability from `false`.

## Execution and trade-offs

1. M01 defers both port calls until subscription, fetches the ordered ID list once
   per subscription, applies `distinct()` and retrieves details using
   `flatMapSequential(..., maxConcurrency, 1)`. Lookups overlap while emissions
   retain first-occurrence input order. Prefetch is 1 because each detail is a Mono.
   Later completions buffer behind earlier details; ordinary `flatMap` would reorder
   results and `concatMap` would serialize latency. No operational default is hidden
   in the application: the caller supplies the positive limit through the constructor.
   Tests exercise limits 1, 2 and 3. M02 configuration supplies the runtime default 4;
   tune with measurements in M04. The service has only final configuration/port
   fields; deduplication and collection state belong to each subscription.
2. M01 collects the complete result with `collectList()` before emitting success.
   M02 maps it before committing the public response so a late failure can still
   produce a coherent HTTP status. The contract returns a JSON array rather than a stream.
   This buffers the result; maximum upstream list size is an open question.
3. Specify connection, response/read and overall request deadlines in M03,
   including pool acquisition and retry time within the total budget. The 50-second
   fixture makes waiting for every detail potentially expensive. No timeout number
   or fallback behavior is approved yet. Cancellation must reach outstanding work.
4. M01 provisionally fails fast for missing or failed details, including an error
   from a later lookup while an earlier lookup is pending. It propagates the original
   error or creates a typed missing-detail error for empty detail completion. No
   placeholders or successful omission fallback are introduced; application itself
   remains free of HTTP status mappings.
   Cancellation and fail-fast termination cancel active publishers without starting
   queued detail calls. No source-existence probe is invented. M02 distinguishes
   missing detail, status, transport and payload failures at the boundary, with
   the mappings above. Deadline/fallback policy remains M03 work, independent of k6 labels.
5. M01 deduplicates similar IDs before detail retrieval, retaining first occurrence.
   M02 rejects mismatching identities at the HTTP boundary, so distinct requested
   IDs cannot collapse onto identical product objects. Object-based deduplication
   is unnecessary. Numeric-ID normalization stays confined to the upstream adapter,
   preserving public string IDs.
6. In M03 evaluate a bounded, expiring detail cache behind the detail port (decorator
   if useful). Successful values only initially; errors must not become long-lived
   cached absences. TTL, maximum entries, availability freshness, in-flight request
   coalescing and whether similar-ID lists should also be cached require decisions.
   Cache changes upstream load and freshness: compare cold and warm runs in M04.
   Caffeine and Resilience4j are candidates, not approved M00 dependencies; add
   either only for a demonstrated need. Avoid retries amplifying 200-VU traffic.

## Test boundaries

- M00: JUnit 5 Boot context test starts real Netty on a random port and uses
  WebTestClient against an unmapped bootstrap probe. It also verifies the upstream
  URL placeholder resolves an explicit override. Separately verify production
  startup on port 5000. No upstream or Docker dependency in this test.
- Domain: plain JUnit tests for only contractual invariants and decimal fidelity.
- M01 application: fake ports, Reactor StepVerifier and controlled TestPublisher
  signals exercise empty lists, first-occurrence deduplication, reversed completion,
  bounded subscriptions, fail-fast errors and cancellation at both lookup stages.
  Repeat subscriptions verify lazy independent state. No Spring context, HTTP,
  Docker, real sleeps, schedulers or execution-timing assumptions are involved.
  `reactor-test` is the only new dependency, test scoped and versioned by Boot.
- M02 REST: controller-bound WebTestClient tests with a stub use case assert route,
  content type, exact fields/types, ordering, error status and empty sanitized bodies.
- M02 HTTP: Reactor Netty loopback stubs test encoded paths, numeric/string IDs,
  exact decimals, strict types, 404/500, malformed JSON and disconnected server.
  Spring integration tests exercise controller -> use case -> HTTP adapter on local
  ports and environment placeholders. A controlled ExchangeFunction tests configured
  concurrency deterministically, without scheduling assumptions or external network.
  Deadline policies remain M03 work.
- Resilience/cache: virtual time and controlled responses test expiration,
  cancellation, failure isolation and concurrent cache misses without real sleeps.
- End to end: supplied Docker mocks plus original k6 script in M04. Retain results,
  host/tool versions, configuration and correctness evidence separately; no
  benchmark thresholds are invented as original requirements.
