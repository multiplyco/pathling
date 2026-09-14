# Pathling JVM benchmark

Status: `complete`. Profile: `full`.

Commit: `18f2431b196fa31952e14e5e58f282330fb454af`. Working tree had changes; source hashes and git.patch are saved. 

Mean time and Criterium's bootstrap interval are in microseconds per call. Allocation includes the measurement loop; its control samples are in run.edn.

| Fixture | Operation | Matches | Mean µs | Interval µs | Bytes/call |
|---|---|---:|---:|---:|---:|
| small-one | path-raw | 1 | 0.362 | 0.360–0.365 | 528.0 |
| small-one | update-array-list | 1 | 0.179 | 0.179–0.180 | 576.0 |
| small-one | raw-roundtrip | 1 | 0.645 | 0.642–0.650 | 1200.0 |
