# Milestones and acceptance criteria

## M00 — Bootstrap and architecture (current scope)

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

## M01 — Domain and application

Create Product, GetSimilarProducts, outbound ports and orchestration with fake
ports. Add Reactor test support only when needed. Resolve relevant questions first.

Acceptance: contractual invariant tests pass; empty results and stable similarity
order under concurrent detail completion are tested; concurrency is bounded;
agreed duplicate/missing/failure outcomes have unit tests. Domain has no framework
dependencies; application imports no HTTP adapters. No real HTTP integration yet.

## M02 — WebFlux REST and HTTP adapters

Wire controller, boundary mapping, WebClient adapter and typed configuration.
Implement approved status mappings and explicit numeric mock ID compatibility.

Acceptance: WebTestClient verifies the supplied successful JSON contract, route,
content type, required types and ordering; stub HTTP tests cover exact upstream
paths, decoding and agreed 404/500/error semantics. Runtime URL override works.
An end-to-end request with supplied mocks works when Docker is available. Supplied
contracts and fixtures remain unchanged.

## M03 — Resilience and caching

Agree timeout/partial-failure/cache policy before coding it. Configure bounded
deadlines and connection resources; evaluate caching and optional libraries.

Acceptance: timeout and cancellation tests pass; slow or failed details behave
according to recorded decisions; TTL, capacity, concurrent misses and cache-error
handling are tested. No blocking event-loop calls or unbounded retries/fan-out.
Record why each added dependency is needed and the accepted freshness trade-off.

## M04 — k6 performance testing and optimization

Start the supplied infrastructure and run the original benchmark before delivery.
Measure first, optimize only demonstrated bottlenecks, then repeat meaningful checks.

Acceptance: execute `docker compose run --rm k6 run scripts/test.js`; preserve the
original script and Compose services. Record commands, environment, cold/warm
conditions, latency percentiles, throughput, errors and upstream call behavior.
Verify response correctness separately; compare against explicit agreed targets.
Report blockers honestly; do not mark this milestone complete without a real run.

## M05 — Final technical audit and README

Audit implementation, contracts, architecture, tests and reproducibility. Add
clear run/test/configuration instructions and technical decisions to the README.

Acceptance: clean wrapper build and tests pass; requirements trace to tests;
M04 evidence is available; error/cache/timeout choices and remaining limitations
are explicit; original challenge materials remain intact. No TODO behavior is
presented as completed. README enables a fresh checkout to run the app and benchmark.

## M06 — GitHub Actions CI after application completion

Only after M01–M05, add a minimal workflow using Java 21 and the Maven Wrapper.

Acceptance: push/PR workflow performs compilation and tests reproducibly without
depending on a developer's Docker daemon; actions have minimal permissions and
pinned reviewed versions. No automatic merge or deploy. After pushing, check CI
once; if pending/running, report and stop until the user returns. Do not poll CI.
