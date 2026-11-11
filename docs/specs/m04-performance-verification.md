# M04 performance verification

## Outcome and scope

The official unmodified benchmark completed with exit code **0** against the actual
Spring Boot application on **5000**, using M03 commit `90f96f3` and its defaults.
It emitted **17,022 HTTP samples**. All original scenarios and fixtures were retained.
Exit 0 proves execution, not a performance acceptance result: the script has no
thresholds or body/status checks. No challenge SLO is supplied.

No production code, dependencies or runtime defaults were changed. A temporary
concurrency-1 candidate was measured and rejected; its process was stopped. Measurements show
bounded transport activity and prompt recovery, but material cold-start rejection
and interference between slow and normal requests. Increasing limits merely to hide
503s would increase simultaneous upstream demand without establishing a correct
capacity/freshness target. Preserve the limits and report their trade-offs.

## Port 5000 and commands

`lsof -nP -iTCP:5000 -sTCP:LISTEN` identified macOS **ControlCenter**, PID 713,
owned by the local user. This is a macOS service, not a disposable development server.
Opened System Settings and selected **General → AirDrop and Continuity → AirPlay
Receiver** using the `orca-computer-use` skill. The switch initially read enabled.
macOS requested local Touch ID/password authentication; the user handled the local
setting. Later read-only verification returned
`defaults -currentHost read com.apple.controlcenter AirplayReceiverEnabled` = **0**,
and lsof showed no listener. No service was killed, no sudo/protected-file/firewall
change occurred. AirPlay Receiver remains **disabled**, leaving 5000 available;
restore it through the same settings switch when no longer needed. Other AirDrop,
Handoff and password settings were not changed by the agent.

Executed from the repository root, with a process-local Java 21 installation:

```sh
./mvnw -B -ntp clean verify

docker compose up -d simulado influxdb grafana
# Build used a process-local Maven repository; no global Maven settings changed.
java -Xlog:gc:file="$M04_TMP/official-gc.log" \
  -jar target/similar-products-api-0.0.1-SNAPSHOT.jar
curl -i http://localhost:5000/product/1/similar
# Restart with a fresh process/cache before the official run.
docker compose run --rm k6 run scripts/test.js
```

The actual build additionally set `JAVA_HOME`, `MAVEN_USER_HOME` and
`-Dmaven.repo.local` to temporary tool/cache directories; no heap tuning was applied.
`M04_TMP` denotes an external temporary evidence directory. No adapted-port official
benchmark was necessary. A preliminary smoke check also ran on 5001 while the port
was being released; the recorded pre-load and official checks used 5000. The
application process was restarted after smoke checks, so official cache state was empty.

The supplied host script is `shared/k6/test.js`, mounted as `/scripts/test.js`.
The exact official command above exported into the supplied InfluxDB database.
The database was empty beforehand; all its HTTP samples were extracted afterward.
Grafana and InfluxDB were running throughout. No monitoring stack was added.

## Environment and configuration

See [environment.json](../performance/environment.json) for exact values:
Java Zulu **21.0.13.0.3**, Boot **3.5.16**, macOS **27.2**, Apple M5 ARM64,
10 physical/logical cores, 16 GiB RAM. Default G1 heap initial 256 MiB and maximum
4 GiB; no explicit heap override. Docker **29.7.2**, Compose **5.5.1**, Docker VM
10 CPUs and about 7.75 GiB. Supplied k6 **0.28.0** and simulado run as amd64 images
under emulation; Grafana/InfluxDB images are ARM64. These affect portability of results.

Application defaults: per-request detail concurrency **4**, pool **32** connections,
pending policy **64**, acquire **500ms**, connect **1s**, response/read **6s**, complete
call **7s**, total execution **10s**, idle **30s**, eviction **15s**. Successful detail
cache **1,000 entries / 30s expire-after-write**; no ID-list cache, retry or coalescing.

## Official scenario scheduling and measurements

All scenarios use 200 constant VUs and 500ms sleep after each request. Windows:
normal 0–10s, notFound 10–20s, error 20–30s, slow 30–40s with up to 10s drain,
verySlow 50–60s with up to 30s drain. First three have gracefulStop 0 and interrupt
unfinished iterations. Slow may drain until 50s; verySlow may drain until 90s.
They do not launch 1,000 simultaneous VUs. Actual k6 execution lasted about 62.6s;
the Docker command included startup overhead and lasted about 65.1s.

Raw samples and reproducible summaries are in [docs/performance](../performance/README.md).
Latency below is **milliseconds, including all statuses**. RPS uses the observed HTTP
span estimate, including actual response drain; its denominator is documented in
`analyze.py`. Scheduled completions/10s are also retained in `results.json`.

| Scenario | Completed | Status counts | RPS | Mean | Median | p90 | p95 | Max |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| normal | 3749 | 200:3602; 503:147 | 376.99 | 29.67 | 15.45 | 68.34 | 104.24 | 325.52 |
| notFound | 3986 | 404:3986 | 398.57 | 9.00 | 3.89 | 21.38 | 35.04 | 129.10 |
| error | 3993 | 502:3989; 503:4 | 399.71 | 13.14 | 4.96 | 44.86 | 57.99 | 124.39 |
| slow | 3089 | 200:1967; 503:740; 504:382 | 311.89 | 151.93 | 4.37 | 503.88 | 509.09 | 5799.52 |
| verySlow | 2205 | 503:1206; 504:999 | 182.70 | 465.73 | 501.26 | 516.60 | 527.31 | 6413.62 |

Exact requests **started** cannot be recovered from the uninstrumented original
script: HTTP counters record completions, and interrupted iterations may already
have completed HTTP and entered sleep. k6 reported 16,427 complete and **600 interrupted
iterations**. Do not add 600 to HTTP completions as a request count. Per-scenario
started counts remain unavailable; supplemental diagnostic counters below measure
starts explicitly. No emitted status-0 transport failure was observed; requests
aborted at scenario boundaries are not evidence of zero client-side cancellation.

### Expected errors versus saturation

- normal: 147 unexpected 503 (**3.92%**); all occur within its first response second.
- notFound: all 3986 responses are expected detail-absence 404. This says nothing
  about existence of the requested source product.
- error: 3989 expected upstream-failure 502; 4 saturation 503 (**0.10%**).
- slow: 1967 successes; 740 overload 503 and 382 timeouts are unexpected for its
  nominal successful fixture (**36.32%** non-200). All these 504s complete under
  2s, consistent with pool waiting rather than the 6s response/read limit.
- verySlow: 1206 overload 503 (**54.69%**). The 999 HTTP 504s are **not all proof
  of the expected 50-second-fixture read deadline**: 935 complete under 2s and only
  64 last at least 2s (in fact about 6s). There are independently **64 Netty
  ReadTimeoutException events** inside the official window. This supports the
  inference that the early 504s are acquisition/resource timeouts, but there is no
  client/server request-ID correlation establishing an exact cause for every sample.
  Only 64/2205 (**2.90%**) show the expected long read-timeout behavior; do not
  advertise all 999 matching status codes as successful resilience-path coverage.

No `http_req_failed` series was emitted by the supplied old k6 version. Status/error
code counts are retained instead. Intentional 404/502/504 are not automatically API
defects. Likewise, short rejection latencies must not be presented as fast successful
service: summaries retain per-status latency as well as the aggregate distribution.

## Cold versus warm comparison

These are **separate diagnostic benchmarks**, not modifications of the official
script. `diagnostic.js` repeats either source 1 or 2 with 200 VUs, 10s active time,
500ms sleep, 10s graceful stop, and counters for starts/completions/body validation.
Every condition used a fresh process and the same M03 limits. Cold had no product
request before load; warm made one complete successful request for that source first.
Warm initialization also warms some JIT/transport state, so this is not an isolated
cache-only causal experiment. Each condition ran once; no statistical claim follows.

| Condition | Started / completed | 200 | 503 | 504 | Mean ms | p95 ms | RPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Cold normal | 3818 / 3818 | 3711 | 107 | 0 | 20.66 | 88.76 | 384.17 |
| Warm normal | 3865 / 3865 | 3865 | 0 | 0 | 16.30 | 72.62 | 391.15 |
| Cold slow | 2994 / 2994 | 1844 | 851 | 299 | 168.52 | 554.18 | 301.87 |
| Warm slow | 3802 / 3802 | 3802 | 0 | 0 | 18.41 | 115.15 | 384.18 |

All **14,479** cold/warm diagnostic response bodies validated, including empty
failure bodies. The later candidate contributes another 2,768 valid responses
(**17,247** total across those diagnostics).
Warm slow removes the observed 38.41% non-200 share and reduces p95 by about 79.2%
relative to cold slow in these runs. This demonstrates useful existing cache behavior,
not a newly implemented optimization or a guaranteed improvement across environments.
The 30-second TTL exceeds each measurement; no freshness setting was relaxed.

No accurate upstream-received request counter or cache-hit counter was available.
Do not infer exact hit rates from timings. Separate DEBUG tracing counted WebClient
attempts (before pool admission), not requests actually received by simulado. Across
its warmup/load/canaries/recovery/expiry it saw 108 attempts for detail 100 and 186
for 1000, demonstrating duplicate cold loads; full counts are retained. Independent
miss semantics were unchanged. Warm recovery took 10.4ms; after a separate idle
31s wait, source 2 took 5.06s again and detail HTTP attempts reappeared, verifying
runtime expiry without changing TTL. This wait was outside benchmark measurement.

Reproduce diagnostics using an external results directory, restarting the application
between conditions; for a warm condition run the source curl before k6:

```sh
docker compose run --rm \
  -v "$PWD/docs/performance/diagnostic.js:/evidence/diagnostic.js:ro" \
  -v "$M04_TMP:/results" -e PRODUCT=2 \
  -e K6_OUT=json=/results/cold-slow-raw.json \
  k6 run /evidence/diagnostic.js
```

## Pool, cancellation and interference

One-second lsof sampling observed at most **32 established upstream sockets** in
every run. Established includes idle connections; it is not an active-request gauge.
A separate diagnostic enabled `logging.level.reactor.netty.resources=DEBUG` and
WebClient attempt logging, then ran cold slow traffic with a normal-source canary
approximately twice a second. Normal details were prewarmed first. DEBUG changes
observation overhead, so its timings are not merged with baseline/comparison data.

The trace observed max **32 active** and transient max **87 pending**. The configured
64 is a pool admission/rejection policy, **not a strict atomic ceiling on its
internal pending counter**. The managed JAR's shaded SimpleDequePool bytecode shows
enqueue/increment before work-in-progress-guarded rejection/draining. The upstream
[pool implementation](https://github.com/reactor/reactor-pool/blob/main/reactor-pool/src/main/java/reactor/pool/SimpleDequePool.java)
corroborates that mechanism; the actual JAR was also inspected to avoid assuming a
moving branch exactly matches the runtime. There were 42 transition snapshots over
64; the first/last were roughly 1.08s apart. These event samples are not a continuous
queue gauge or a hard worst-case bound. No second application semaphore was added.

Of 23 canaries, **15 returned 200, 7 returned 503, 1 returned 504**; their bodies were
valid. Thus shared-pool protection can reject unrelated fast traffic when slow misses
occupy the same dependency. It protects upstream concurrency but does not provide
per-route fairness or public admission control. Pending and active counts drained to
zero; final trace state was 0 active, 4 idle, 0 pending before shutdown. Idle sockets
are permitted and later evicted; do not call every retained socket a cancellation leak.
Recovery requests succeeded, including after expiry. Provider disposal and absence
of new queued work after cancellation remain covered by the existing deterministic
M03 tests. These short traces cannot prove leak-free operation for arbitrary duration.

## JVM observations

The official sampler covered 71.57s including load/drain/recovery. Heap used ranged
**18.85–47.27 MiB**, ending **34.85 MiB**; committed max **54 MiB**, versus a 4 GiB
maximum. GC counters advanced **46 collections / 69ms**; the GC log contained no
full collection. Maximum live Java threads **34**. Mean process CPU was **0.224 core
equivalents**; the maximum sampled process load was **14.48% of 10-core capacity**
(about 1.45 cores). These are JVM process metrics, not host or Docker CPU totals.

No growing heap/thread trend or CPU/GC bottleneck was demonstrated in the short
run. No heap dump/histogram was taken; exact cache object overhead is unmeasured.
Only a few fixture IDs can be cached, so this does not evaluate a 1,000-entry workload.
The default JVM heap maximum is much larger than observed use, but there is no basis
here to prescribe a deployment heap setting. Docker/emulation/host noise and the
monitoring overhead remain limitations. A native macOS DNS-resolver startup warning
fell back to the system resolver; no measured request failure was attributed to DNS.

## Optimization decision and quality gates

Measured bottleneck: duplicate cold detail loads consume globally shared connections,
then pool waiting/rejection dominates while cache entries are populated. Very slow
details never populate a success cache and keep this pressure until cancellation.

Considered hypotheses:

- More connections/pending slots or longer acquisition wait could increase cold
  completion but also amplify upstream load or delay rejection; no dependency
  capacity/SLO/fairness requirement supports selecting a larger value from this run.
- Coalescing duplicate misses could reduce load, but changes ownership/cancellation
  semantics and needs a deliberately tested design. It was not silently introduced.
- Longer TTL could avoid reloads but weakens price/availability freshness. No agreed
  freshness contract justifies changing it.
- A breaker could reduce outage attempts but would not establish fair capacity for
  valid slow requests. No Resilience4j, retry or extra admission queue was added.

One focused runtime experiment followed the baseline: set only
`--upstream.max-concurrency=1` on a fresh process for the same cold-source-2
200-VU diagnostic. Hypothesis: reducing per-request fan-out might reduce shared-pool
pressure. Counter-effect: the 100ms/1s/5s details become serial. Results:

| Cold slow configuration | Completed | 200 | 503 | 504 | Non-200 | Mean ms | p95 ms | RPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline concurrency 4 | 2994 | 1844 | 851 | 299 | 38.41% | 168.52 | 554.18 | 301.87 |
| Candidate concurrency 1 | 2768 | 1381 | 906 | 481 | 50.11% | 232.39 | 561.98 | 278.40 |

The candidate adds 11.70 percentage points of non-200 responses, raises mean latency
about 37.9%, reduces completed successful responses about 25.1%, and lowers observed
throughput about 7.8%. p95 changes only slightly; the aggregate mixes fast rejections
and successes. This single-run comparison supports rejecting this candidate, not
claiming universal statistical significance. All 2,768 started requests completed;
all bodies validated and post-load recovery passed. The candidate process was
stopped and its override discarded. No source or production default was edited.

Final retained production change is **none**; concurrency stays **4**. This is a documentation and
evidence milestone. Priorities for a future agreed scope are cold-miss coalescing,
public admission/fairness and explicit capacity/freshness targets, not blind tuning.

`./mvnw clean verify` passed **166 tests, zero failures/errors/skips**; production
and test sources stayed unchanged afterward. New extraction tools reproduced raw
HTTP CSVs byte-for-byte, and recomputing `results.json` matched exactly. No new
dependency, public field, endpoint, status mapping or default-port change occurred.
Before/after curl checks validate exact ordered fixture bodies with Decimal parsing;
404/502/504 bodies remain empty and no internal details are disclosed. Supplemental
load checks validate JSON structure/types, IDs, names, prices, order and availability.
Decimal precision, duplicates, empty-list behavior, expiry, cancellation and error
cause mappings additionally remain covered by all existing tests; no live empty-list
fixture is invented. No partial-list success or fabricated product was observed.

All temporary application/JMX sampler processes were stopped. Supplied Docker
services remain available. No generated classes, large logs/JSON streams, heap dumps,
Docker data, user-specific paths or secrets are committed. Compact raw HTTP rows,
resource samples, summaries and portable observation/extraction sources are retained.
M05 README/audit and M06 CI are explicitly not started.
