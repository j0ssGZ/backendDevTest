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

## Planned package layout

M00 created the entry point, YAML and context test. Domain and application paths
are implemented in M01; infrastructure paths below remain planned for M02.

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
    http/ProductApiClient.java
    http/UpstreamProductResponse.java
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
Typed properties and HTTP client configuration arrive with M02.

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
  both ports and a positive `maxConcurrency`; Spring wiring is deferred to M02.

`Product` is a Java record. ID, name and price are non-null; ID and name are
non-empty, with no trimming/nonblank restriction. `BigDecimal` is retained without
rounding or scale conversion; zero and negative values remain legal. Availability
is a primitive boolean. JSON required-field/null validation, including distinguishing
missing availability from `false`, belongs to the future adapter.

## Execution and trade-offs

1. M01 defers both port calls until subscription, fetches the ordered ID list once
   per subscription, applies `distinct()` and retrieves details using
   `flatMapSequential(..., maxConcurrency, 1)`. Lookups overlap while emissions
   retain first-occurrence input order. Prefetch is 1 because each detail is a Mono.
   Later completions buffer behind earlier details; ordinary `flatMap` would reorder
   results and `concatMap` would serialize latency. No operational default is hidden
   in the application: the caller supplies the positive limit through the constructor.
   Tests exercise limits 1, 2 and 3. Choose the runtime value in M02 configuration,
   then tune with measurements in M04. The service has only final configuration/port
   fields; deduplication and collection state belong to each subscription.
2. M01 collects the complete result with `collectList()` before emitting success.
   Later, collect before writing the public response so a late failure can still map to
   a coherent HTTP status. The contract returns a JSON array rather than a stream.
   This buffers the result; maximum upstream list size is an open question.
3. Specify connection, response/read and overall request deadlines in M03,
   including pool acquisition and retry time within the total budget. The 50-second
   fixture makes waiting for every detail potentially expensive. No timeout number
   or fallback behavior is approved yet. Cancellation must reach outstanding work.
4. M01 provisionally fails fast for missing or failed details, including an error
   from a later lookup while an earlier lookup is pending. It propagates the original
   error or creates a typed missing-detail error for empty detail completion. No
   placeholders, partial/empty-success fallback or HTTP status mapping is introduced.
   Cancellation and fail-fast termination cancel active publishers without starting
   queued detail calls. No source-existence probe is invented. Future adapters must
   distinguish lookup failure, missing detail, server error, timeout and malformed
   JSON; agree externally visible mappings in M02/M03, independently of k6 labels.
5. M01 deduplicates similar IDs before detail retrieval, retaining first occurrence.
   Distinct IDs returning identical or mismatched details remain an upstream-boundary
   question; no object-based deduplication is invented. Proposed numeric-ID
   normalization remains confined to the future upstream adapter and must not
   change the public string-ID contract.
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
- REST: WebTestClient slice tests with a stub use case assert route, status,
  content type, exact fields, types and ordered array semantics.
- HTTP adapter: local HTTP stub exercises paths, numeric-versus-string fixtures,
  decoding, 404/500, malformed JSON and deadlines. Use a minimal test-only server;
  choose additional test tooling only when needed.
- Resilience/cache: virtual time and controlled responses test expiration,
  cancellation, failure isolation and concurrent cache misses without real sleeps.
- End to end: supplied Docker mocks plus original k6 script in M04. Retain results,
  host/tool versions, configuration and correctness evidence separately; no
  benchmark thresholds are invented as original requirements.
