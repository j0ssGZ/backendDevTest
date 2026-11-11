# Requirements

## Authority and scope

The supplied `readme.md`, `similarProducts.yaml`, `existingApis.yaml`,
`docker-compose.yaml`, `shared/simulado/mocks.json` and `shared/k6/test.js`
are preserved. OpenAPI defines the HTTP contract; mock observations below do
not silently amend it. M00 delivered bootstrap and planning; M01 implements
domain values and application orchestration only. HTTP integration is deferred.

## Required API behavior (future implementation)

- Listen on port **5000** and expose `GET /product/{productId}/similar`
  (`operationId: get-product-similar`). The required path parameter is a string;
  the contract defines no numeric restriction.
- Call `GET /product/{productId}/similarids` upstream, then
  `GET /product/{similarId}` for the returned IDs.
- Preserve the upstream similarity order when returning product details.
- Return `200 application/json` with a JSON array, allowing an empty array and
  requiring unique items. Do not wrap the array in another object.
- Each item requires `id` and `name` (strings with minimum length 1), `price`
  (number), and `availability` (boolean). The schema specifies neither a price
  range/currency nor nonblank strings, sorting by price, or availability filtering.
- The public API declares `404 Product Not found` without a response body schema.
  Its precise trigger and error representation are unspecified.
- Upstream similar IDs are declared as a unique array of strings (possibly empty),
  already ordered by similarity. Only `200` is declared for that operation.
  Upstream product detail declares `200` and `404`.
- No pagination, authentication, persistence, retries, cache policy, latency SLO,
  or public upstream-error status mapping is specified.

## Supplied fixtures and benchmark

- Mock base URL: `http://localhost:3001`. Public base URL: `http://localhost:5000`.
- Similar-ID fixtures: `1 -> [2,3,4]`, `2 -> [3,100,1000]`,
  `3 -> [100,1000,10000]`, `4 -> [1,2,5]`, `5 -> [1,2,6]`.
  These are JSON **numbers**, conflicting with the string ID contract.
- Detail IDs 1–4 succeed; 5 returns 404; 6 returns 500. Configured delay values
  are 100 for ID 3, 1000 for 100, 5000 for 1000 and 50000 for 10000
  (intended milliseconds; runtime verification remains pending).
- The k6 labels `notFound` and `error` refer to source products 4 and 5 whose
  *similar details* include failures; they do not specify the required public
  status or prove the source product is absent.
- The actual script is `shared/k6/test.js`, mounted as `/scripts/test.js`, rather
  than `shared/k6/scripts/test.js`. The supplied command is
  `docker compose run --rm k6 run scripts/test.js`.
- k6 sends requests to `host.docker.internal:5000`, with five scenarios of
  200 constant virtual users each and 10 seconds of active traffic. Starts are
  0, 10, 20, 30 and 50 seconds; graceful stops are 0, 0, 0, 10 and 30 seconds.
  Every iteration sleeps 0.5 seconds. The script has no response assertions or
  pass/fail thresholds; HTTP measurements alone do not prove contract compliance.
- Compose supplies simulado, InfluxDB, Grafana and k6; implement only the app.

## Engineering constraints

Use Java 21, stable compatible Spring Boot, WebFlux, Maven Wrapper, JUnit 5 and
WebTestClient. Keep the domain independent of Spring and HTTP. Clarity,
maintainability, resilience, performance and unit testing guide implementation.
Do not add Caffeine or Resilience4j in M00. Execute the original k6 benchmark
before final delivery, after functionality exists; do not execute it in M00.
CI is deferred to M06. Never automatically merge or deploy.

## M01 provisional application policy

These are explicit implementation decisions for M01, not claims about unspecified
OpenAPI behavior. Revisit failure policy in M02/M03 if verified evidence warrants it.

- A successful empty ID list emits one empty product list. ID lookup completing
  without a list is a port-contract error, not a missing-source signal.
- Duplicate similar IDs are retrieved once, preserving their first occurrence.
- Detail absence fails the whole use case with `MissingProductException`, carrying
  the missing detail ID. Do not return placeholders, omit details or infer a source 404.
- Preserve upstream errors and their causes; do not recover failures as successful
  empty or partial lists. Fail fast even if an earlier detail is still pending.
- Fetch details with a positive constructor-supplied concurrency limit and preserve
  similarity order. Cancellation reaches active work; queued lookups are not started.
- No HTTP status mapping, timeout, retries or cross-request caching is added in M01.

See [design](design.md), [milestones](tasks.md) and
[unresolved decisions](open-questions.md).
