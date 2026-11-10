# Development and M00 verification

## Run locally

Install or select a Java 21 JDK for the current shell, then use the wrapper;
no global Maven installation is necessary. The first invocation downloads Maven
and dependencies and requires network access.

```sh
./mvnw clean verify
./mvnw spring-boot:run
```

The server listens on port 5000. M00 has no product controller; a 404 is expected
for requests until M02. Configure the upstream for a single process with:

```sh
UPSTREAM_BASE_URL=http://localhost:3001 ./mvnw spring-boot:run
```

Alternatively use `--upstream.base-url=...` as an application argument. The URL
is configuration only in M00; no outgoing request is made. Tests use a random
server port to avoid conflicts and never require the upstream.

For later mock verification (daemon must be running):

```sh
docker compose config --quiet
docker compose up -d simulado influxdb grafana
curl -i http://localhost:3001/product/1/similarids
```

The full benchmark is deferred to M04. See the original README for its commands.

## Inspected environment

- Repository was clean on `main`; origin's default branch is `main`.
- `origin`: `https://github.com/j0ssGZ/backendDevTest.git` (confirmed fork).
- `upstream`: `https://github.com/dalogax/backendDevTest.git` (reference only).
- macOS arm64: no usable JDK registered and no `mvn` on PATH at inspection.
  Validation tools are extracted under `/tmp/similar-products-m00-tools` only;
  no shell profile, global Java selection or system setting is changed.
- Docker CLI 29.7.2; Docker Compose v5.5.1.
- `docker compose config --quiet`: passed, with the existing obsolete `version`
  key warning. Supplied Compose remains unchanged.
- `docker info`: cannot connect to the daemon socket. No mock services were
  started, and live mock readiness is **unverified**. Docker availability is a
  blocker for mock verification and the later benchmark, not the context test.
  A direct request to `localhost:3001/product/1/similarids` also failed to connect.
- Port 5000 is already bound by macOS `Control Center` (confirmed with `lsof`).
  Do not disable system services or change the required application port to hide
  this conflict. Use `--server.port=0` temporarily for a startup smoke check.
- No Java tests/build existed before M00. The supplied k6 script contains traffic
  scenarios but no assertions or thresholds. No full benchmark was run in M00.

## Verification results

- Validation runtime: Azul Zulu Java **21.0.13.0.3**, macOS arm64; Maven
  **3.9.16** invoked through Wrapper **3.3.4**. The JDK SHA-256 was verified
  against Azul metadata. The Maven distribution SHA-512 was checked against
  Maven Central; the wrapper also pins its SHA-256.
- Build command (with process-local `JAVA_HOME` pointing to the temporary JDK
  and `MAVEN_USER_HOME=/tmp/similar-products-m00-tools/m2`):

  ```sh
  ./mvnw -B -ntp -Dmaven.repo.local=/tmp/similar-products-m00-tools/repository clean verify
  ```

- Context test: **1 test, 0 failures, 0 errors, 0 skipped**. Real Netty startup,
  WebTestClient HTTP exchange and upstream URL override all passed. Compiled
  application bytecode has major version 65 (Java 21).
- `clean verify`: **BUILD SUCCESS**; executable artifact generated at
  `target/similar-products-api-0.0.1-SNAPSHOT.jar` (ignored by Git).
- Non-failing diagnostics: Mockito reports dynamic agent attachment on Java 21;
  Netty reports its macOS native DNS resolver is unavailable and falls back to
  system resolution. No additional dependency was added for these local warnings.
  Revisit native DNS behavior if needed when building the HTTP adapter in M02.

- Executable startup using the temporary JDK:

  ```sh
  java -jar target/similar-products-api-0.0.1-SNAPSHOT.jar
  java -jar target/similar-products-api-0.0.1-SNAPSHOT.jar --server.port=0
  ```

  The default invocation failed with `Port 5000 was already in use`, matching
  the pre-existing system listener. The second invocation started Netty on port
  58390; `GET /__bootstrap_probe` returned 404 as expected for an unmapped route.
  The smoke-check process was stopped afterwards. Configuration remains port 5000;
  successful binding to that exact port requires resolving the local conflict.
- Full diff reviewed; original contracts, README, diagram, mocks, Compose services,
  Grafana assets and k6 script are unchanged. No CI workflow was added (M06).
