# Initial single-match reference

One full-profile JVM run of the three primary operations, on the `:small-one`
fixture (40 visited nodes, one match). See `summary.md` and `run.edn`.

This records the unchanged v0.2.1 library with the new benchmark harness. It is a
focused reference, not coverage of the entire workload matrix or an estimate of
between-process variability. Repeat full runs with matching settings before
using small timing differences to make implementation decisions.

The run completed in Codex's macOS sandbox. Current-thread allocation accounting
worked, but CPU-model lookup and Babashka's process-tree cleanup queries were
blocked by the sandbox. The command exited successfully; the CPU model is `nil`
in the metadata. JVM version, OS, architecture, processor count, and heap/GC
settings are recorded. No asynchronous Quiescent work was measured.

The directory was copied from the original `:output` path recorded in `run.edn`.
`sources.zip` preserves the exact library, harness, tests, and build sources.
