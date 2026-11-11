# M04 retained evidence

Read [the verification report](../specs/m04-performance-verification.md) for decisions
and limitations. These are single-host measurements, without invented pass thresholds.
A temporary concurrency-1 candidate performed worse and was discarded. No
production optimization or configuration change was retained.

- `environment.json`: runtime, machine, limits and AirPlay change.
- `official-k6-summary.txt`: compact final console output; trailing whitespace normalized, progress logs omitted.
- `runs.json`: execution metadata and diagnostic counters.
- `*-http.csv`: every emitted HTTP-duration sample from the corresponding run.
- `results.json`: reproducible status distributions, percentiles and rates,
  including the rejected `candidate-serial-slow` runtime experiment.
- `*-jvm.csv`, `*-sockets.csv`, `resources.json`: sampled JVM/socket evidence.
- `official-timeouts.csv`: 64 Netty read-timeout events during the official window.
- `resource-trace*.csv`, `resource-trace.json`: separate instrumented pool/canary
  diagnostic, including cleanup, TTL reload and WebClient attempt counts.
- `correctness.json`: exact fixture-body and status checks before/after load.

HTTP CSV timestamps are milliseconds relative to that run's first completed response;
`duration_ms` is raw k6 `http_req_duration`. It excludes blocked/connect time and the
script's 500ms sleep. Scenario tags and original status/error codes are retained.
Missing error codes are blank. There were no status-0 transport samples.

`analyze.py` uses linearly interpolated percentiles over individual samples. It emits
both completions/10 scheduled seconds and an approximate observed-HTTP-span rate.
The latter uses the last response end minus the earliest end minus duration. Neither
is the full Docker-command rate: container initialization and drain periods differ.
Status conformity does not establish the cause of a 504. The 2-second bucket is an
analysis discriminator, never an API timeout or a performance acceptance threshold.

Recompute and compare:

```sh
python3 docs/performance/analyze.py > "$M04_TMP/recomputed.json"
diff -u docs/performance/results.json "$M04_TMP/recomputed.json"
```

For a new official run, retain its raw Influx samples separately. Restrict the query
to that run's time window if the database contains earlier runs:

```sh
curl -sS -G http://localhost:8086/query --data-urlencode db=k6 \
  --data-urlencode epoch=ns --data-urlencode 'q=SELECT * FROM http_req_duration' \
  > "$M04_TMP/influx.json"
python3 docs/performance/extract_http.py influx "$M04_TMP/influx.json" "$M04_TMP/official-http.csv"
```

For diagnostic JSON-lines output:

```sh
python3 docs/performance/extract_http.py k6 "$M04_TMP/cold-slow-raw.json" "$M04_TMP/cold-slow-http.csv"
```

JVM samples use a temporary local JMX management agent started through the JDK
Attach API. `JvmProbe.java` reads memory, CPU time/load, thread count and GC counters
once per second; its sleep is only a monitoring interval. Compile outside the repo:

```sh
"$JAVA_HOME/bin/javac" --add-modules jdk.attach -d "$M04_TMP" docs/performance/JvmProbe.java
"$JAVA_HOME/bin/java" --add-modules jdk.attach -cp "$M04_TMP" JvmProbe "$APP_PID" "$M04_TMP/jvm.csv" 100
```

Retained JVM timestamps are relative; cumulative CPU/GC counters retain their
original values. `resources.json` reports deltas across the sampling interval,
including initialization/drain/recovery, not only load. The first probe's decimal
locale used a comma; that one CPU-load column was normalized during extraction.
Sockets are `lsof` counts of established application connections to upstream port
3001, including idle pool sockets; they are not active-request or pending metrics.
Pool activity comes from the separate DEBUG trace. Heavy DEBUG logging was excluded
from official and cold/warm timing comparisons.

Large JSON streams, application logs, class files, Docker data and local sampling
harnesses remain outside version control. Extractors were checked byte-for-byte
against all retained HTTP rows; the summary recomputation is identical.
