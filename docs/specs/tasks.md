# Milestones and acceptance criteria

## M00 — Bootstrap and architecture (completed)

- [x] Inspect clean Git state, fork/default branch, original contracts, Compose,
  all mock routes and existing tests (only the supplied k6 script exists).
- [x] Establish Java 21, stable compatible Boot, WebFlux, Maven Wrapper, JUnit 5,
  WebTestClient, port 5000 and an externally configurable upstream URL.
- [x] Document requirements, architecture, future milestones and unresolved behavior.
- [x] Verify wrapper compilation, test/context and URL override; check executable
  startup (port 5000 blocked locally; random-port smoke check passed).
- [x] Validate Compose; report Docker/mock blockers without claiming live readiness.
- [x] Review complete diff and preserve all challenge assets.

Delivery step: commit `chore: bootstrap Spring Boot and architecture`, push to
the fork's `main` branch and stop. Record the delivery hash in the handover.

Acceptance: build and context pass where tooling can be provisioned; actual
results/blockers are recorded. No similar-products implementation, cache,
resilience library, benchmark run or CI workflow. Stop after commit/push; M01
requires explicit user approval.

## M01 — Domain and application (completed)

- [x] Immutable Product record with contractual null/empty checks and exact
  BigDecimal storage, without additional price or whitespace restrictions.
- [x] GetSimilarProducts, SimilarProductIds and ProductDetails contracts, plus
  constructor-injected orchestration with an explicit positive concurrency cap.
- [x] Ordered concurrent detail retrieval, first-occurrence ID deduplication,
  lazy independent subscriptions and cancellation of pending work.
- [x] Provisional fail-fast absence/error policy documented; typed missing-detail
  outcome and original error causes preserved without HTTP mappings.
- [x] Deterministic unit tests with fake ports and controlled publishers;
  Reactor Test is the only new dependency, test scoped and Boot managed.
- [x] `./mvnw clean verify` passed: **31 tests, 0 failures/errors/skips**
  (10 domain, 20 application, 1 existing Boot context test).
- [x] Import review and `jdeps` confirm the domain depends only on `java.base`;
  application has no Spring/HTTP/infrastructure imports or blocking operations.

TDD evidence: domain tests first failed before Product existed, then all 10
passed. Application tests ran against an empty service: 17 behavioral failures;
after orchestration was implemented, all 20 passed. The final clean verification
includes the unchanged M00 test. No new unit test needs Spring, HTTP or Docker.

Acceptance: contractual invariant tests pass; empty results and stable similarity
order under concurrent detail completion are tested; concurrency is bounded;
agreed duplicate/missing/failure outcomes have unit tests. Domain has no framework
dependencies; application imports no HTTP adapters. No real HTTP integration yet.

Delivery: one commit `feat: implement similar-products domain and use case`,
push to `origin/main`, inspect CI once and stop. M02 requires explicit approval.

## M02 — WebFlux REST and HTTP adapters (completed)

- [x] Thin GET controller with exact four-field JSON boundary and no orchestration
  duplicated outside the existing use case.
- [x] WebClient adapter for both outbound ports, strict JSON validation, exact
  decimal handling, local ID normalization, encoded URI variables and identity checks.
- [x] Typed infrastructure properties and wiring; existing URL configuration
  retained, positive per-request concurrency configurable with default 4.
- [x] Explicit empty-body error mappings without source-existence inference,
  retries, omission fallback, caching or application-interface changes.
- [x] Automated controller, local HTTP adapter, wiring and Spring integration tests.
- [x] `./mvnw clean verify`: **121 tests passed**, zero failures/errors/skips.
  The 31 existing tests remain unchanged; 90 new parameterized cases cover M02.
- [x] Supplied simulado started; actual normal, missing, 500, unknown and slow
  responses inspected. A JAR started with SERVER_PORT=5001 and curl verified the
  endpoint against Docker, including successful ordered results and error mappings.
- [x] Evidence and remaining M03 questions recorded in
  [M02 verification](m02-verification.md). Full benchmark remains deferred to M04.

Acceptance: WebTestClient verifies the supplied successful JSON contract, route,
content type, required types and ordering; stub HTTP tests cover exact upstream
paths, decoding and agreed 404/500/error semantics. Runtime URL override works.
An end-to-end request with supplied mocks works when Docker is available. Supplied
contracts and fixtures remain unchanged.

Delivery: commit `feat: expose similar-products REST API with WebClient`, push to
`origin/main`, inspect CI once and stop. M03 requires explicit user approval.

## M03 — Resilience and caching (completed)

- [x] Inspect framework defaults and run the unchanged baseline: 121 tests passed.
- [x] Record failure, timeout, overload and cache policy before implementation.
- [x] Configure validated finite TCP, acquisition, response, call and overall
  execution limits; disable automatic retries and preserve M02 error distinctions.
- [x] Own a bounded upstream pool with finite pending admission, idle eviction
  and disposal; retain per-request concurrency 4 without redundant limiters.
- [x] Add successful-detail Caffeine caching with configurable capacity/expiry,
  exact values, identity checks and independent cancellable misses.
- [x] Document freshness trade-offs and defer coalescing/circuit breakers.
- [x] Add 45 deterministic/controlled regression cases; `./mvnw clean verify`
  passes **166 tests**, zero failures/errors/skips, including all original 121.
- [x] Start the production JAR on 5001 against unchanged Docker mocks: fast,
  1 s, 5 s, 50 s, missing, 500 and unknown cases verified. The 50 s fixture
  returns 504 in approximately 6 s; a separate 200 ms overall override also works.
- [x] Record configuration, automated and live evidence in
  [M03 verification](m03-resilience-verification.md); preserve challenge assets.

Acceptance: timeout and cancellation tests pass; slow or failed details follow
recorded decisions; TTL, capacity, concurrent misses and cache-error behavior are
tested. No blocking production operations, unbounded retry/queue or added owned
thread pool. Only Caffeine is added; domain/application and API contracts are unchanged.
No k6 benchmark, speculative tuning, CI workflow or deployment in this milestone.

Delivery: commit `feat: add bounded resilience and product caching`, push to
`origin/main`, inspect CI once and stop. M04 requires explicit user approval.

## M04 — k6 performance testing and optimization (completed)

- [x] Inspect the exact scenario windows and preserve supplied scripts/fixtures.
- [x] Free port 5000 through supported AirPlay Receiver settings; verify disabled
  state without terminating a macOS service. Record the restoration procedure.
- [x] Run `docker compose up -d simulado influxdb grafana`, then the exact official
  `docker compose run --rm k6 run scripts/test.js` against the actual application.
- [x] Record 17,022 raw HTTP samples, status-specific latency, expected versus
  saturation outcomes and the original script's request-start/interrupt limitations.
- [x] Compare separate fresh-process cold/warm runs with unchanged limits;
  all 14,479 diagnostic response bodies validate and starts/completions are counted.
- [x] Sample JVM/socket use; separately trace pool saturation, fast-request
  interference, transient pending-counter overshoot, cleanup and runtime TTL expiry.
- [x] Measure one focused concurrency-1 candidate against cold slow baseline;
  non-200 responses worsen from 38.41% to 50.11%. Discard the runtime override,
  retain concurrency 4 and all production defaults. No new dependency/coalescing.
- [x] `./mvnw clean verify`: 166 tests pass; extraction and summary reproduction
  checked. Confirm contracts, mocks, Compose, k6, source/tests and port default unchanged.
- [x] Retain compact evidence under `docs/performance/` and decisions in
  [M04 verification](m04-performance-verification.md). Temporary app/samplers stopped.

Acceptance: the real official execution and independent correctness verification
are complete. No performance pass threshold is invented. Single-host measurements
and unresolved cold-overload/fairness/freshness constraints are explicit; M05/M06
remain untouched. AirPlay Receiver stays disabled so 5000 remains available.

Delivery: commit the benchmark evidence, push to `origin/main`, inspect CI once and
stop. M05 and M06 require explicit user approval.

## M05 — Final technical audit and README (completed)

- [x] Review original requirements/contracts, all implementation boundaries and SDD;
  classify compliance with source/test evidence and distinguish user constraints.
- [x] Document accepted 404, freshness, cold-load, fairness and pending-counter
  limitations without changing externally visible behavior or historical measurements.
- [x] Rewrite README with exact run/build/test/configuration/k6/stop instructions,
  verified JSON example, actual defaults and links to detailed evidence.
- [x] Fresh HTTPS clone of M04 commit with no target output and empty wrapper/Maven
  artifact caches: Maven 3.9.16, Java 21, dependency resolution and clean JAR build pass.
- [x] All 166 tests pass; isolated clone-mounted Docker mock and default-port 5000
  packaged-JAR integration pass. Optional 5001/deadline override and Maven run pass.
- [x] Review source/dependency hygiene, tracked assets/secrets/generated files,
  README links and version/test-count consistency; preserve supplied challenge assets.
- [x] Record verdict **READY WITH DOCUMENTED LIMITATIONS** in
  [final delivery audit](../qa/final-delivery-audit.md); no benchmark rerun without need.

Acceptance: independent clean build and real integration pass; requirements trace
into evidence and limitations remain explicit. Original README is replaced as
requested; its brief remains in Git. No new feature, policy, dependency, CI or deployment.

Delivery: commit `docs: finalize Java backend delivery audit`, push to `origin/main`,
inspect CI once and stop. Actual challenge submission and M06 require explicit approval.

## M06 — GitHub Actions CI after application completion

Only after M01–M05, add a minimal workflow using Java 21 and the Maven Wrapper.

Acceptance: push/PR workflow performs compilation and tests reproducibly without
depending on a developer's Docker daemon; actions have minimal permissions and
pinned reviewed versions. No automatic merge or deploy. After pushing, check CI
once; if pending/running, report and stop until the user returns. Do not poll CI.
