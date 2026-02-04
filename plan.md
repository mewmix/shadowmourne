Optimization Plan for Antlers-Class Devices

Goals
- Reduce open Downloads to ≤2s on Antlers-class phones.
- Cut initial search latency from 13s → ≤3–4s; Enter-triggered search ≤2–3s.
- Maintain accuracy and responsiveness under CPU and I/O contention.

Current Observations
- Open Downloads: ~5s (large directory, heavy stat + sort path).
- Search: ~13s initial, ~7s after Enter (deep traversal, many writes).
- Native core: uses getdents64; parallel stat for listing; parallel search with a work queue; SIMD/NEON prefilter for first/second characters.

Likely Bottlenecks
- Stat storm: stat for every entry even when sorting by name/type.
- Sorting: qsort across large arrays with string comparisons and per-entry allocations.
- Work queue pressure: malloc/free per item; global buffer lock contention across threads.
- Directory reads: 4 KB getdents64 buffer causing excess syscalls.
- Search match: partial NEON prefilter; substring scan remains scalar.
- JNI/buffer churn: small local buffers force frequent flushes to the global buffer.

Immediate Changes (Phase 1)
1) Lazy stat by sort mode
   - If sorting by name/type: skip stat during initial list; only stat the visible window after sort (e.g., first 200 entries) and on-demand as the user scrolls.
   - If sorting by size/time: stat only when needed and page the work (top N entries), deferring the rest.

2) Larger dirent buffer
   - Increase getdents64 read buffer from 4 KB → 64 KB (heap-allocated), reducing kernel calls and context switches.

3) Adaptive thread counts
   - Determine cores via sysconf(_SC_NPROCESSORS_ONLN).
   - Listing stat threads: min(cores, 6–8) when sorting by size/time; otherwise 0 (lazy) or minimal.
   - Search workers: min(max(cores, 4), 8) to better hide I/O latency; clamp for Antlers.

4) Reduce lock contention and JNI writes
   - Increase LOCAL_BUF_SIZE from 16 KB → 64 KB to batch writes.
   - Ensure global writes occur as large chunks; avoid small flushes.

5) Sort streamlining
   - Partition dirs/files first in O(n), then qsort each partition by the chosen key.
   - Fast ASCII case-insensitive compare: lowercase on the fly up to a fixed block (e.g., 64 bytes) and memcmp; tie-break with full compare only when needed.

Near-Term SIMD/NEON Targets (Phase 2)
- Case-insensitive substring match: NEON blockwise fold + seed locate + block compare for typical ASCII names.
- Name compare hotpath: NEON-accelerated strcasecmp for the first 16–64 bytes.

Instrumentation and Profiling
- Add internal timing counters: scan, stat, sort, output; count dir entries, stat calls, flush sizes.
- Simpleperf (device):
  - adb shell simpleperf record -p $(pidof com.mewmix.glaive) -g --duration 10 -o /sdcard/perf.data
  - adb pull /sdcard/perf.data
  - simpleperf report-html -o report.html perf.data
- Perfetto trace (10–15s windows) around open/search for sched + fs + userspace stacks.

ADB Load Simulation (No Root)
- CPU contention: adb shell 'for i in $(seq 1 4); do (yes >/dev/null &) done' and stop via adb shell pkill yes.
- Constrain CPUs: adb shell taskset -p 3 $(pidof com.mewmix.glaive) (mask 0x3 = CPUs 0–1).
- I/O pressure: adb shell 'dd if=/dev/zero of=/data/local/tmp/io_stress bs=4M count=64 oflag=sync' ; then remove file.

Rollout Plan
1) Implement Phase 1 (lazy stat, larger dirent buffer, adaptive threads, larger local buffer, partitioned sort).
2) Benchmark on Antlers under idle and simulated load; capture metrics and traces.
3) Iterate parameters (thread caps, buffer sizes) based on data.
4) Implement Phase 2 NEON for substring and name compare where it shows up hot.
5) Final verification: cold/warm open of Downloads, search scenarios, and UI smoothness.

Acceptance Metrics
- Downloads open ≤2s for 10k+ entries directory (name sort, lazy stat).
- Initial search ≤4s across typical storage; Enter-triggered ≤3s.
- No ANRs; stable memory; reduced syscalls per directory scan.

