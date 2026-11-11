# M02 verification

## Environment and commands

Verified on 2026-11-11 using Java 21.0.13.0.3, Maven Wrapper 3.3.4/Maven 3.9.16,
Spring Boot 3.5.16, Docker 29.7.2 and Compose v5.5.1 on macOS arm64.

```sh
docker compose up -d simulado
curl -i http://localhost:3001/product/1/similarids
./mvnw clean verify
SERVER_PORT=5001 UPSTREAM_BASE_URL=http://localhost:3001 UPSTREAM_MAX_CONCURRENCY=4 \
  java -jar target/similar-products-api-0.0.1-SNAPSHOT.jar
curl -i http://localhost:5001/product/1/similar
```

Java and Maven were selected with process-local JAVA_HOME/MAVEN_USER_HOME pointing
to the temporary M00 tool installation; dependencies used its temporary repository.
No global setting changed. The original Compose file was not edited. Its obsolete
version-key warning remains, and the supplied amd64 mock image ran successfully
on the arm64 host. Only simulado was started; no k6 benchmark was run.
Observed image digest:
`ldabiralai/simulado@sha256:e1a3287457a4d1978fc0c2de80756a3b8afb39039a38cb8196c0295943208e3c`.

Port 5000 remains occupied by macOS Control Center. The JAR successfully started
on 5001 via environment override; application default remains 5000. The application
smoke-test process was stopped afterwards; the requested mock container remains up.

## Observed upstream responses

These are live curl observations from Docker, not inferred contract behavior.
JSON content types below were `application/json; charset=utf-8`.

- `GET /product/1/similarids`: **200**, JSON, `[2,3,4]`.
- `GET /product/4/similarids`: **200**, JSON, `[1,2,5]`.
- `GET /product/5/similarids`: **200**, JSON, `[1,2,6]`.
- `GET /product/2`: **200**, JSON,
  `{"id":"2","name":"Dress","price":19.99,"availability":true}`.
  Similar IDs are numeric in the fixtures; detail IDs are strings.
- `GET /product/5`: **404**, JSON, `{"message":"Product not found"}`.
- `GET /product/6`: **500**, JSON content type, **empty body**.
- `GET /product/unknown/similarids`, `/product/0/similarids` and
  `/product/unknown`: **404**, `text/plain; charset=utf-8`, body `Not Found`.
  This generic router response does not establish a source-product absence rule.
- `GET /product/100`: **200**, JSON, approximately **1.037 s**,
  `{"id":"100","name":"Trousers","price":49.99,"availability":false}`.
- `GET /product/1000`: **200**, JSON, approximately **5.036 s**,
  `{"id":"1000","name":"Coat","price":89.99,"availability":true}`.
- `GET /product/10000`: **200**, JSON, approximately **50.058 s**,
  `{"id":"10000","name":"Leather jacket","price":89.99,"availability":true}`.

There is no valid empty-similarities fixture in the supplied configuration; no live
`200 []` was observed. Empty similarities and string similar-ID arrays are exercised
by deterministic local stubs. No challenge fixtures were added or changed.
The slow-request timings are smoke observations, not benchmark results or SLOs.

## Observed public endpoint against Docker

`GET http://localhost:5001/product/1/similar` returned **200 application/json**:

```json
[
  {"id":"2","name":"Dress","price":19.99,"availability":true},
  {"id":"3","name":"Blazer","price":29.99,"availability":false},
  {"id":"4","name":"Boots","price":39.99,"availability":true}
]
```

Only the four required fields were present; IDs/names were strings, price numeric
and availability boolean. Ordering matches the upstream similar-ID list.

- `/product/2/similar`: **200 application/json**, approximately **5.027 s**,
  IDs `3,100,1000` with their complete details in that order.
- `/product/4/similar`: **404**, `Content-Length: 0`, empty body, no content type.
  Missing detail 5 causes the existing fail-fast policy; it does not prove source 4
  is missing.
- `/product/5/similar`: **502**, `Content-Length: 0`, empty body, no content type.
  Detail 6 returns 500; successful partial results are not returned.
- `/product/unknown/similar`: **502**, `Content-Length: 0`, empty body, no content
  type. The generic upstream lookup 404 is not converted into a source-not-found claim.

Connection failure -> **503** and valid empty similarities -> **200 []** are
verified through local HTTP stubs, not claimed as live Docker observations.
All mapped application/dependency failure responses tested are empty, without
upstream URLs, payloads, stack traces, exception names or internal messages. Extra 502/503/500 outcomes are
implementation-specific extensions; supplied OpenAPI files remain unchanged.

## Automated verification

`./mvnw clean verify` completed with **BUILD SUCCESS: 121 tests, 0 failures,
0 errors, 0 skipped**:

- Existing tests: 10 domain, 20 application, 1 Boot context; unchanged and passing.
- HTTP adapter: 63 cases for numeric/string/integral-decimal IDs, bad JSON/ID types,
  required fields, exact prices, content types, mismatched identity, encoded paths,
  unexpected statuses, detail absence and disconnected transport.
- REST: 12 cases for exact route, field names/types, ordered array, empty result,
  encoded string source ID, unavailable routes, content negotiation and sanitized errors.
- Wiring: 7 cases for typed binding, invalid configuration, base-path URL override,
  shared outbound adapter and configured concurrency. A controlled ExchangeFunction
  verifies the cap and reverse completion synchronously, without timing assertions.
- Full local Spring/HTTP integration: 8 cases for the real adapter chain, environment
  URL/concurrency placeholders, empty array and missing/status/payload/identity errors.

Automated tests require neither Docker nor an external network, only loopback
servers. No sleeps or timing assertions are used in new tests. Existing non-failing
Mockito agent and macOS native DNS fallback diagnostics remain. Invalid-property
tests intentionally exercise startup binding failures and log their warnings.

M03 still needs agreed deadlines, cancellation/deadline interaction, resource
budgets and cache/freshness policy. The 50-second fixture remains slow in M02 by
design; no timeout/fallback/retry policy was introduced to hide it.
