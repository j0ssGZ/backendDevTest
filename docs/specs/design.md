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

Paths below are planned; M00 creates only the entry point, YAML and context test.

```text
src/main/java/com/example/similarproducts/
  SimilarProductsApplication.java
  domain/Product.java
  application/
    GetSimilarProducts.java
    GetSimilarProductsService.java
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

## Planned execution and trade-offs

1. Fetch the ordered ID list once, then retrieve details with bounded concurrency.
   Prefer `flatMapSequential` with an explicit concurrency limit: detail requests
   overlap while emissions retain input order. Ordinary `flatMap` would reorder
   results; `concatMap` would serialize latency. Decide the limit with measurements
   in M04; do not use unbounded fan-out or block Netty event-loop threads.
2. Collect before writing the public response so a late failure can still map to
   a coherent HTTP status. The contract returns a JSON array rather than a stream.
   This buffers the result; maximum upstream list size is an open question.
3. Specify connection, response/read and overall request deadlines in M03,
   including pool acquisition and retry time within the total budget. The 50-second
   fixture makes waiting for every detail potentially expensive. No timeout number
   or fallback behavior is approved yet. Cancellation must reach outstanding work.
4. Distinguish source-ID lookup failure, detail 404, detail 5xx, timeout and malformed
   JSON. Do not turn every failure into `[]` or map a missing similar detail to a
   missing source product automatically. Partial success versus fail-fast is an
   unresolved product decision; record it before implementing externally visible
   failure behavior. Do not infer it from k6 scenario names.
5. Preserve uniqueness and first-occurrence similarity if duplicates need defensive
   handling, but decide ID-versus-object deduplication semantics first. Proposed
   numeric-ID normalization is confined to the upstream adapter, explicitly tested,
   and must not change the public string-ID contract.
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
- Domain: plain unit tests for only contractual invariants and decimal fidelity.
- Application: deterministic fake outbound ports, later Reactor StepVerifier for
  empty lists, ordering under out-of-order completion, concurrency bounds and
  agreed failure semantics. Do not load Spring here.
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
