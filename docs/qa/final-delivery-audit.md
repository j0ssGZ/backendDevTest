# Final delivery audit — M05

**Verdict: READY WITH DOCUMENTED LIMITATIONS**

Audited on 2026-11-11 against application commit
`1688e05d29742a87b5639863de6f2d3c4171b10e`. M05 changes documentation only.
The required success contract, architecture, build and real integration are supported
by tests and a separate fresh-dependency checkout. No confirmed critical/high-severity
blocker was found. Cold overload, shared-pool interference, freshness and ambiguous
404 semantics remain limitations, not problems claimed to have been fixed.
This is a challenge-submission assessment, not production/security certification.

## Authority and review method

The original technical brief is the supplied README, preserved in Git at
[the original challenge revision](https://github.com/dalogax/backendDevTest/blob/6486a1fcd0e3eb8bbc740a2368be2189632e603f/readme.md),
plus [the diagram](../../assets/diagram.jpg), [public OpenAPI](../../similarProducts.yaml)
and [upstream OpenAPI](../../existingApis.yaml). No separate PDF/Word challenge
was found among repository files. Reviewed all production classes, test boundaries,
SDD documents, dependency declarations, wrapper, Compose, mocks and k6 script.

Mandatory original requirements are Spring Boot, the endpoint/port and successful
OpenAPI behavior. Clarity, performance and resilience are evaluation dimensions,
without quantitative targets. Java 21, hexagonal boundaries, tests, reactive
orchestration and delivery milestones are additional user constraints. Do not
reinterpret recommendations as extra original mandatory requirements.

Classifications: **COMPLIANT**, **PARTIALLY COMPLIANT**, **NOT COMPLIANT**,
**NOT VERIFIED**. A missing target or unsupported certification claim is not silently
converted into compliance. No definite mandatory failure was identified requiring
NOT COMPLIANT; the partial/unverified rows below remain explicit.

## Requirement-to-implementation matrix

Source paths below are relative to `src/main/java/com/example/similarproducts/`;
test names refer to `src/test/java/com/example/similarproducts/`.

| Requirement | Authority | Classification | Implementation and evidence |
| --- | --- | --- | --- |
| Exact GET /product/{productId}/similar | Original mandatory | COMPLIANT | infrastructure/rest/SimilarProductsController; route/unknown-route controller tests; fresh-JAR curl |
| Default port 5000 | Original mandatory | COMPLIANT | application.yaml; fresh JAR bound to 5000 with no port override |
| Required successful JSON schema/types | Original mandatory | COMPLIANT | ProductResponse emits exactly id/name/price/availability; strict HTTP and controller tests; real JSON checked with Decimal parsing |
| Empty successful array | Original mandatory | COMPLIANT | GetSimilarProductsService collects an empty ID list as []; application/controller/full Spring HTTP integration tests |
| Similarity ordering | Original mandatory | COMPLIANT | flatMapSequential with explicit concurrency/prefetch; reversed-completion fake-port tests and exact real array checks |
| Unique similar products | Original mandatory | COMPLIANT | distinct IDs retain first occurrence; HTTP identity validation prevents different requested identities collapsing; duplicate tests |
| Both existing upstream operations | Original mandatory | COMPLIANT | ProductApiClient requests /product/{id}/similarids and /product/{id}; encoded-path tests and isolated real mock |
| Java/Spring Boot | Original Boot requirement + Java 21 user constraint | COMPLIANT | pom.xml Boot 3.5.16 / Java 21; fresh wrapper build and packaged-JAR execution |
| Public 404 semantics | Original response declared, trigger unspecified | PARTIALLY COMPLIANT | Missing detail maps to empty 404; source absence is not established. Generic similarids 404 becomes 502, as tested and observed |
| Hexagonal architecture and clarity | User constraint / original evaluation dimension | COMPLIANT | Pure domain, application-owned ports, explicit infrastructure wiring/decorators; dependency/import review and jdeps |
| Unit and integration tests | User constraint | COMPLIANT | 166 passing cases, including 10 domain/20 application; WebTestClient, fake ports, loopback HTTP and Spring tests |
| Reactive bounded concurrency | User constraint; reactive approach encouraged | COMPLIANT | Reactor ports and lazy flatMapSequential; cancellation and bounds verified without Spring in application tests; no production blocking/manual subscriptions |
| Resilience and resource protection | Original evaluation dimension + user policies | COMPLIANT | Owned 32-connection pool, finite admission/acquisition policy, call/overall deadlines, cancellation and sanitized mappings; no claim of a strict 64-counter ceiling |
| Product caching correctness | User M03 constraint, not original API mandate | COMPLIANT | CachingProductDetails: positive identity-validated immutable values, finite TTL/capacity; expiry/error/precision/cancellation tests |
| Supplied performance benchmark executed | User delivery constraint / original self-evaluation tool | COMPLIANT | M04 official unmodified script, 17,022 samples and exit 0; raw evidence retained; no SLO-pass claim |
| Capacity/performance evaluation objective | Original qualitative evaluation; no numeric target | PARTIALLY COMPLIANT | Working warm behavior and bounded failure, but cold/high-load rejection and fast-request interference measured; not all 200-VU requests succeed |
| Documentation/reproducibility | User M05 constraint | COMPLIANT | Rewritten README, exact configuration, fresh remote clone, empty Maven caches, packaged app and isolated Docker smoke verified |
| Absence of dependency/image vulnerabilities | Security-review scope; no original certification requirement | NOT VERIFIED | Managed dependency/source hygiene inspected; no current vulnerability-database or container-image scan performed |

These classifications establish implemented behavior, not automated OpenAPI-schema
certification or a numerical coverage percentage. The supplied 404 trigger remains
a contract question; no missing-source heuristic was added to improve the matrix.

Key automated evidence: [domain](../../src/test/java/com/example/similarproducts/domain/ProductTests.java),
[orchestration](../../src/test/java/com/example/similarproducts/application/GetSimilarProductsServiceTests.java),
[HTTP adapter](../../src/test/java/com/example/similarproducts/infrastructure/http/ProductApiClientTests.java),
[Spring HTTP integration](../../src/test/java/com/example/similarproducts/infrastructure/SimilarProductsIntegrationTests.java),
[Netty limits](../../src/test/java/com/example/similarproducts/infrastructure/config/NettyResourceLimitsTests.java),
[overall deadline](../../src/test/java/com/example/similarproducts/infrastructure/resilience/DeadlineGetSimilarProductsTests.java)
and [cache](../../src/test/java/com/example/similarproducts/infrastructure/cache/CachingProductDetailsTests.java).

## Architecture review

- **Domain:** Product record retains non-null/non-empty contractual fields and exact
  BigDecimal scale/precision. No invented positive-price/stock/currency rule.
  Fresh `jdeps Product.class` reports only `java.base` dependencies.
- **Application:** inbound GetSimilarProducts and outbound SimilarProductIds /
  ProductDetails are application-owned. Reactor coupling is explicit; no Spring,
  WebClient, HTTP, Jackson or infrastructure imports. Constructor injection and
  subscription-local deduplication/collection avoid cross-request mutable state.
- **Orchestration/cancellation:** deferred port calls, first-occurrence distinct,
  flatMapSequential(maxConcurrency, 1), then collectList. Failures do not become
  partial success. Controlled publishers verify cancellation, later-error fail-fast,
  ordered completion and absence of newly started queued work after termination.
- **HTTP:** strict local ObjectReader, encoded URI variables and required JSON types;
  exact integral numbers normalize locally to string IDs. Identity mismatch fails.
  Empty, malformed and oversized bodies do not become empty successful products.
  The controller maps only four public fields. Error causes remain internal.
- **Failure policy:** detail absence -> 404; unexpected status/payload -> 502;
  transport/pool rejection -> 503; timeout -> 504; internal failure -> 500.
  Generic lookup 404 is a dependency failure, not evidence of source absence.
  No policy was changed during this audit.
- **Resources/deadlines:** Spring owns/disposes the dedicated ConnectionProvider;
  it shares existing Netty event loops. TCP 1s, acquisition 500ms, response read
  inactivity 6s, complete call 7s and once-per-use-case 10s deadline. No retry,
  redundant semaphore, manual thread pool or unbounded application queue was added.
  The outer timer bounds aggregation, not downstream socket delivery/serialization.
  Actual pending-counter overshoot and missing public admission/fairness remain explicit.
- **Cache:** bounded successful details only, 1,000 entries, expire-after-write 30s,
  direct maintenance executor, shutdown invalidation. No failures/absence/ID-list
  cache or orphaned shared Mono load. Independent misses may duplicate loads;
  one caller's cancellation does not cancel another caller. A valid detail can be
  cached even when a different detail makes its containing request fail.
- **Validation:** typed constructors reject invalid pool/concurrency/cache settings;
  resilience durations are at least 1ms and TCP milliseconds fit Netty's integer
  range. Existing environment aliases and all documented defaults match source.
- **Dependencies/security:** two direct production dependencies, WebFlux starter
  and Caffeine; test starter and Reactor Test are test-scoped. Boot manages versions.
  No database/Servlet stack/unused resilience library was found. Recognizable private
  key/API-token patterns were absent from tracked text; this is not a full-history,
  entropy-based secret scan. Sanitized response tests cover internal-data disclosure.
  Authentication/TLS are not challenge requirements. Supplied local Grafana is
  intentionally anonymous-admin and is not assessed as a secure deployment.

Source review found no production `.block()`, `.subscribe()`, sleeps, manual executor
or retry loop. Short test/probe lifecycle waits do not run in application event loops.
The implementation's internal shaded-pool exception-name classification is version
sensitive; real Netty tests protect current behavior but upgrades need review.

## Independent clean-checkout verification

Created a separate temporary clone **over HTTPS from origin/main**, pinned to the
commit above. It had no target directory or copied build output. Set a new empty
`MAVEN_USER_HOME` and an initially absent local artifact repository; no global Maven
settings or previous dependency cache was reused. Wrapper downloaded Maven **3.9.16**
and resolved dependencies/plugins from the configured public repositories.

Commands, with `M05_TMP` standing for that external temporary directory:

```sh
git clone --depth 1 https://github.com/j0ssGZ/backendDevTest.git "$M05_TMP/checkout"
cd "$M05_TMP/checkout"
"$JAVA_HOME/bin/java" -version
MAVEN_USER_HOME="$M05_TMP/maven-home" ./mvnw -v
MAVEN_USER_HOME="$M05_TMP/maven-home" ./mvnw -B -ntp \
  -Dmaven.repo.local="$M05_TMP/repository" clean verify
docker compose -p similar-products-m05 config --quiet
docker compose -p similar-products-m05 up -d simulado
"$JAVA_HOME/bin/java" -jar target/similar-products-api-0.0.1-SNAPSHOT.jar
```

With the application running, from another terminal:

```sh
curl -i http://localhost:5000/product/1/similar
```

Java was selected with a process-local `JAVA_HOME` (Azul Zulu **21.0.13.0.3**, macOS
ARM64); the existing JDK itself was reused, not freshly installed. No user profiles
or global Java/Maven setting changed. The clone resolved approximately 75 MiB of
Maven artifacts and a new wrapper distribution; **clean verify passed in 32.952s**
(a one-run observation, not a build-time guarantee). It produced the executable JAR.
All **166 tests passed, zero failures/errors/skips**. The source checkout stayed clean.

The existing original simulado was temporarily stopped to release 3001; a separately
named Compose project started the clone's mounted fixtures. After verification the
isolated project was removed and the original simulado was restarted. Grafana and
InfluxDB were left intact. Compose validity passed with its inherited obsolete
`version` warning. Existing Docker images were reused; no clean image pull or fresh
Docker/JDK installation is claimed. Maven dependencies/build outputs were fresh.

A second packaged process verified `SERVER_PORT=5001` plus
`RESILIENCE_REQUESTTIMEOUT=200ms`: source 2 -> empty 504. The README's optional
`spring-boot:run` path also started from the cloned project/cache and served an
ordered 200 JSON response on 5001. All temporary app processes were stopped.
No macOS preference changes were made in M05; 5000 was available following M04.
Windows/Linux host startup and an entirely fresh OS installation remain unverified.

## Real integration observations

Fresh-clone packaged JAR, default configuration, host port **5000**, supplied mock:

| Source | HTTP | Observed seconds | Verification |
| --- | ---: | ---: | --- |
| 1 | 200 | 0.233977 | Exact fields/types/Decimal prices and ordered IDs 2,3,4 |
| 2 | 200 | 5.036185 | Exact ordered IDs 3,100,1000; no omitted detail |
| 3 | 504 | 6.051762 | Empty body; 50s fixture bounded by current read timeout |
| 4 | 404 | 0.031977 | Empty; an individual detail is absent |
| 5 | 502 | 0.022064 | Empty; upstream detail HTTP 500 |
| unknown | 502 | 0.017613 | Empty; generic upstream lookup 404, no source-absence inference |

Successful JSON matched the immutable supplied fixture values, including availability
false and exact Decimal prices; errors contained no internal exception information.
The optional Maven-run path independently verified `application/json` headers.
No live empty-list fixture exists: that case is verified by deterministic full Spring
HTTP integration tests, not falsely presented as a Docker observation.

## Official benchmark evidence

[M04 report](../specs/m04-performance-verification.md) and
[compact raw evidence](../performance/README.md) were reviewed; their measurements
were not rewritten. Recomputing `results.json` from retained HTTP rows matched.
The official command is exactly `docker compose run --rm k6 run scripts/test.js`,
targeting `host.docker.internal:5000`. Only its invocation/mount/networking instructions
were rechecked; M05 did **not** repeat the full benchmark because production behavior
and dependency declarations are unchanged.

M04 produced 17,022 responses and exit 0, without assertions or pass thresholds.
Cold normal had 3.92% rejection; official slow had 36.32% non-200. Warm diagnostics
had no non-200 responses. Many verySlow 504s were early acquisition/resource timeouts,
not demonstrations of the intended read deadline. A concurrency-1 candidate worsened
cold slow non-200 share from 38.41% to 50.11% and was discarded. These are single-run
observations with emulated amd64 k6/simulado on ARM, not portable SLO guarantees.

## Findings, severities and disposition

Severity reflects challenge-delivery impact. Accepted does not mean corrected.

| Finding | Severity | Disposition |
| --- | --- | --- |
| Missing-detail 404 may be interpreted as source absence; source-not-found cannot be proven through the supplied lookup | Medium | Accepted contract ambiguity; documented in README and open questions; no silent mapping/probe change |
| Cold duplicate loads cause rejection and shared-pool interference | Medium | Accepted measured limitation; independent misses and current bounds retained; no unconditional throughput claim |
| Pending policy 64 is not an atomic ceiling; trace peaked at 87 | Medium | Documented accurately; active cap and finite timeout/rejection remain tested; no strict total-memory/admission guarantee |
| Price/availability may retain cached values for 30s; no version ordering or byte budget | Medium | Explicit provisional trade-off; positive-only TTL/capacity tests; freshness targets still open |
| No maximum contractual result size, public admission/fairness or long-duration leak proof | Medium | Accepted scope/measurement limitation; no claim that the pool bounds all incoming memory |
| Shaded exception-name mapping depends on managed Netty version | Low | Existing real-pool tests; review on dependency upgrades |
| Vulnerability absence / clean Docker-image reproducibility not verified | Low | No clean-security claim; pinned Maven versions inspected, images reused and supplied mutable simulado tag preserved |
| Original README lacked implemented run/configuration/error guidance | Low | Addressed by README overhaul and verified commands; no behavior fix claimed |
| Requirements/development documentation contained historical M00/M02 status wording | Low | Current overview corrected; historical verification marked historical without changing observations |

No critical/high confirmed defect was found requiring disruptive changes or stopping
delivery. The qualitative performance objective is only partially met under cold
bursts; readiness is conditional on exposing these limitations to the evaluator.
No new business feature, optimization, dependency or externally visible policy was added.

## Repository hygiene and pre-submission actions

Original OpenAPI, mocks, Compose, Grafana assets, k6 script and diagram match original
challenge revision `6486a1f` byte-for-byte. Only the README is intentionally replaced
under M05 authorization; its original brief remains available through Git. No CI,
PR, deployment or challenge submission was performed. M06 is still separate.

Reviewed tracked file sizes and recognizable secret patterns; no new binaries,
credentials, large logs, temporary dependency caches or Docker data are included.
Existing compact benchmark CSVs remain intentional evidence, not stray artifacts.
Relative README/audit links resolve; source/default values and test totals match.

Before actual submission: provide the evaluator this README/audit and disclose the
404/freshness/load trade-offs. Seek clarification of those business expectations or
performance targets if the evaluator requires stronger behavior. No mandatory SLO
was invented. Obtain explicit approval before M06 CI or any further implementation;
this milestone does not submit the repository on the user's behalf.
