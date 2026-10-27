# FIX latency baseline (Phase 0)

The "before" numbers for Phase 1 engine work, measured with the Phase 0 tooling (commits
`6a9204b`, `ba4af85`) on Windows loopback. Rerun the same commands after each Phase 1 change and
compare like with like. AWS runs live in `docs/latency-history.jsonl`.

## Setup

| | |
|---|---|
| Machine | AMD Ryzen 7 6800H laptop (8 cores / 16 threads), 15 GB RAM, Windows 11 Pro, power plan *Balanced* |
| JVM | Microsoft OpenJDK 17.0.20.1, `-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:+AlwaysPreTouch -Dagrona.disable.bounds.checks=true` (from the script) |
| Topology | `SampleInitiator` and `SampleAcceptor` on one machine over loopback, both event loops busy-spinning, no CPU pinning |
| Load | 20,000 warmup orders (unpaced), 10 s pause, then 100,000 timed NewOrderSingles at 1,000/s; RTT = order to ExecutionReport (New) |
| Persistence | off (`log-messages = false`) unless stated; "persistence" = both sides write every message to Chronicle, no tracing |

```bash
scripts\test\run-latency-test.bat 20000 100000 1000 --busy-spin                        # headline
scripts\test\run-latency-test.bat 20000 100000 1000 --busy-spin --stages               # breakdown
scripts\test\run-latency-test.bat 20000 100000 1000 --busy-spin --persistence
scripts\test\run-latency-test.bat 20000 100000 1000 --busy-spin --stages --persistence
scripts\test\run-latency-test.bat 20000 100000 100 --busy-spin                         # old default rate
mvn -Pbenchmarks -pl benchmarks -am package -DskipTests && java -jar benchmarks\target\benchmarks.jar -prof gc
```

## Phase 1, item 3: no allocation per message, µs

`getMsgType()` returns interned MsgTypes, `getDouble()` parses from the buffer, SendingTime is
encoded without `java.time`, `SessionConfig.getSessionId()` is built once, `FixSession`'s message
listeners are an array (no iterator per message), and the acceptor writes `ORD<n>`/`EXEC<n>` without
a String. `AcceptorAllocationTest` measures the event loop's allocation per order: **559 B before,
0 after**.

Old jar (`b1e881e`, B) and new (A) alternately, same commands as below (100k at 1,000/s after a
20k warmup, `--gc`). RTT from intended send:

| Run | min | p50 | p90 | p99 | p99.9 | p99.99 | max | mean | young GCs in the test (acceptor) |
|-----|----:|----:|----:|----:|------:|-------:|----:|-----:|---:|
| busy-spin, pair 1, before | 18.9 | 30.0 | 75.7 | 147.1 | 246 | 958 | 3,697 | 42.0 | 3 |
| busy-spin, pair 1, after | 17.7 | 34.0 | 84.5 | 163.3 | 309 | 2,031 | 3,453 | 45.7 | 1 |
| busy-spin, pair 2, before | 17.5 | 29.6 | 76.0 | 153.1 | 276 | 3,570 | 10,543 | 42.5 | 3 |
| busy-spin, pair 2, after | 16.5 | 21.8 | 44.3 | 92.4 | 160 | 1,090 | 5,140 | 28.0 | 1 |
| no busy-spin, after | 38.1 | 74.6 | 131.3 | 207.0 | 278 | 474 | 2,460 | 86.3 | 1 |
| no busy-spin, before | 41.3 | 85.3 | 151.4 | 227.7 | 304 | 612 | 2,447 | 98.7 | 3 |

- **Young GCs during the 100 s test: 3 → 1 on the acceptor in every pair** (the one left comes
  from other threads: admin, metrics, the JIT). Whole-run young pauses 16 → 12.
- **Latency on this laptop is within noise:** the two busy-spin pairs disagree (+4 and −8 µs at
  p50), min is 1–3 µs lower in all three pairs, no busy-spin −11 µs at p50. The gain expected was
  small at p50 (a few hundred ns of allocation and cache traffic per message) and in the tail
  only through fewer GCs; AWS (pinned cores) is the better place to see it.

## Phase 1, items 1 and 2: no selector wakeup, FIX read cap (`e688195`, `d76566a`), µs

Item 1: a commit no longer wakes the selector when the loop busy-spins or when it runs on the
loop thread. Item 2: FIX reads take up to `read-cap-bytes` (2048), not 25 header bytes and then
the rest of one message.

This machine was much busier than for the baseline below (browsers, a video call): the
unchanged code ran at p50 56–80 µs instead of 48–49. So the old jar (`92dd417`, B) and the new
one (A) ran alternately under the same conditions, same commands and setup as below; compare
within a pair, not with the older tables. RTT from intended send:

| Run | min | p50 | p90 | p99 | p99.9 | p99.99 | max | mean |
|-----|----:|----:|----:|----:|------:|-------:|----:|-----:|
| busy-spin, pair 1, before | 31.8 | 69.6 | 129.9 | 238.1 | 567 | 3,373 | 8,372 | 81.7 |
| busy-spin, pair 1, after | 20.0 | 54.1 | 111.4 | 207.7 | 444 | 4,309 | 10,117 | 66.2 |
| busy-spin, pair 2, before | 30.9 | 56.0 | 95.7 | 155.5 | 269 | 487 | 2,234 | 63.5 |
| busy-spin, pair 2, after | 21.8 | 40.9 | 75.3 | 122.9 | 217 | 391 | 1,640 | 47.9 |
| no busy-spin, before | 47.0 | 93.1 | 155.3 | 238.1 | 303 | 380 | 1,096 | 103.7 |
| no busy-spin, after | 37.6 | 86.5 | 177.0 | 278.8 | 382 | 612 | 2,335 | 107.3 |
| busy-spin, stages on, after | 17.4 | 25.4 | 61.3 | 133.0 | 234 | 1,099 | 2,839 | 35.0 |

- **Busy-spin: p50 −15 µs and min −10 µs in both pairs**, p99 −30 µs. Per round trip that is
  two `Selector.wakeup()` calls (one per side, each a lock + pipe write on the sending thread,
  then an extra select return on the loop) and two `read()` calls + two select returns per
  message per side.
- **`send.commit_call` (sender time in the ring commit) fell from 3.5 µs mean to 0.04 µs**; the
  acceptor's `recv.callback`, which commits the ExecutionReport, from 5.9 to 2.0 µs mean.
- **No busy-spin: p50 −7 µs, tails within noise (higher here).** In normal mode only loop-thread
  commits skip the wakeup; a blocked `select()` dominates either way.
- **Read cap hits in the paced phase: 0** on both sides. In the unpaced 20,000-order warmup
  burst the initiator hit the cap 850–1,300 times and the acceptor 1,120–1,265 (logged once at
  Logout, never per hit). Burst acks: first 3.6–6.5 ms, p50 2.0–2.5 ms, p99 10–13 ms; that is the
  burst queueing behind itself (up to the runner's pending-order limit), not the read cap alone.
- The stages-on run (p50 25 µs) happened to land in a quiet spell; its stage report is the
  useful part, not its absolute RTT.

## Current baseline: after the event-loop allocation fix, µs

The first baseline (below, at `7be58cc`) had two problems in the measuring setup itself, found
with JFR, GC logs and the stage report:

- **The busy-spin loop allocated about 180 MB/s.** `NetworkEventLoop.drainAllChannelRingBuffers`
  iterated `selector.keys()`, twice per spin, and each pass created two iterator objects (99% of
  the event-loop thread's allocation). That caused a young GC about every 0.85 s on both sides,
  each about 1 ms. Now it walks a cached key array.
- **The sample listeners logged on the event loop.** In latency mode the acceptor still logged
  every 100th message and the initiator every 1,000th, synchronously to the console and a file.
  This was the acceptor's `recv.callback` p99 of ~118 µs. Now they don't log in latency mode, and
  `FixSession` no longer formats the raw message for a disabled `log.debug` on every message.

Same setup and commands as below.

| Run | Column | min | p50 | p90 | p99 | p99.9 | p99.99 | max | mean |
|-----|--------|----:|----:|----:|----:|------:|-------:|----:|-----:|
| base, run 1 | intended | 30.2 | 47.9 | 82.3 | 158.9 | 304 | 2,570 | 5,333 | 55.8 |
| | actual | 30.2 | 47.8 | 81.5 | 157.3 | 296 | 2,157 | 5,333 | 55.2 |
| base, run 2 | intended | 30.0 | 49.0 | 88.7 | 166.8 | 344 | 2,673 | 4,702 | 59.1 |
| | actual | 29.8 | 48.8 | 87.7 | 164.0 | 321 | 2,245 | 4,702 | 58.5 |
| persistence | intended | 33.2 | 54.7 | 95.9 | 180.6 | 387 | 1,825 | 3,795 | 64.1 |
| stages | intended | 31.4 | 49.5 | 89.0 | 173.1 | 368 | 3,135 | 10,207 | 59.7 |

Against the first baseline (base runs, intended column):

| | before | after |
|---|---:|---:|
| p50 | 57.7–60.5 | 47.9–49.0 |
| p99 | 227–246 | 159–167 |
| p99.9 | 780–845 | 304–344 |
| p99.99 | 2,320–2,472 | 2,570–2,673 |
| young GCs during the measured 100 s (acceptor) | 87 | 0–1 |
| event-loop thread allocation (JFR estimate, acceptor, whole run) | 17.6 GB | 84 MB |

- **p99.99 did not move.** With GC gone, the stalls of 1–3 ms that remain are elsewhere, most
  likely the OS descheduling one of the unpinned threads on this laptop (the pacer's send slip
  shows stalls of the same size on the sending thread). Pinning cores or an AWS run is needed to
  see further into that tail.
- **The acceptor listener is no longer an outlier:** `recv.callback` p99 is 20.9 µs (was 118.5).
  Its mean (5.9 µs) is still the ring commit + `selector.wakeup()` plus the sample app's own
  allocation (`getDouble`, `getMsgType`, `"ORD"+n`, `"EXEC"+n`).
- **Socket writes got shorter too** (`send.write` mean 19.6 µs initiator / 15.8 µs acceptor,
  was 26.2 / 18.2), and `recv.session` fell from 2.2 to 0.8 µs. Less garbage streaming through
  the caches is the likely reason; not separately confirmed.
- The remaining allocation per message (about 700 B on the acceptor's event loop) comes from
  `getMsgType()`, `getDouble()`, `encodeSendingTime` (`ZonedDateTime`) and the sample
  listener's string concatenation: the Phase 1 allocation items.

## First baseline (at `7be58cc`, before the fix): round trip, µs

"Intended" is measured from each order's scheduled send time, so it is corrected for coordinated
omission. "Actual" is measured from the moment the order was handed to the session, the same as
the old summary table.

| Run | Column | min | p50 | p90 | p99 | p99.9 | p99.99 | max | mean |
|-----|--------|----:|----:|----:|----:|------:|-------:|----:|-----:|
| base, run 1 | intended | 34.7 | 60.5 | 112.3 | 245.8 | 845 | 2,472 | 3,691 | 74.4 |
| | actual | 34.7 | 60.2 | 111.4 | 236.8 | 716 | 1,759 | 3,691 | 73.4 |
| base, run 2 | intended | 35.0 | 57.7 | 103.4 | 226.8 | 780 | 2,320 | 5,423 | 70.4 |
| | actual | 35.0 | 57.5 | 102.7 | 220.2 | 627 | 1,977 | 5,419 | 69.6 |
| persistence | intended | 37.6 | 66.3 | 123.5 | 267.5 | 838 | 2,134 | 5,693 | 81.3 |
| | actual | 37.5 | 66.1 | 122.6 | 258.8 | 700 | 1,637 | 5,345 | 80.3 |
| stages, run 1 | intended | 35.4 | 64.4 | 125.8 | 268.5 | 887 | 2,605 | 8,245 | 80.5 |
| stages, run 2 | intended | 36.1 | 62.9 | 113.7 | 242.6 | 828 | 2,515 | 3,598 | 76.8 |
| stages + persistence | intended | 38.2 | 69.5 | 135.9 | 293.4 | 933 | 2,310 | 4,375 | 86.2 |
| base at 100/s | intended | 63.1 | 142.9 | 224.3 | 352.3 | 910 | 2,468 | 8,446 | 155.5 |
| | actual | 63.0 | 141.2 | 220.4 | 341.0 | 834 | 1,535 | 4,710 | 152.9 |

- **Run-to-run spread.** The two base runs differ by about 3 µs at p50 and 20 µs at p99, so
  differences smaller than that need repeated runs before they count.
- **Coordinated omission.** The intended and actual columns agree up to p99. They split at p99.9
  and beyond (for example p99.99 2.5 ms vs 1.8 ms), because a stall delays the orders queued behind
  it. The pacer itself is on time: send slip p50 is 0.2 µs and p99 3.6 µs, with occasional stalls
  up to about 2 ms (p99.99 ≈ 1 ms), which are scheduling pauses of the sending thread.
- **Stage timing costs about 4–5 µs at p50** (about 6 µs mean) on this machine; it is consistent
  across both pairs of runs. Use runs with stage timing off for the headline numbers, and runs with
  it on for the breakdown.
- **Persistence adds about 6 µs at p50 and 7 µs mean.** On AWS the same switch adds about 50 µs
  at p50 (below), so the cost is much higher there.
- **Rate.** At 100 orders/s the p50 is more than twice the p50 at 1,000/s, even though both loops
  busy-spin. A longer gap between orders seems to leave caches and branch predictors cold, or let
  the laptop's power management slow the cores; the cause is not confirmed. The ~65 µs quoted
  before Phase 0 matches the 1,000/s numbers.

## Where the time went in the first baseline (stage timing, mean µs, stages run 1)

Means add up along each chain; percentiles do not.

| Segment | Mean | p50 | p99 | Notes |
|---------|-----:|----:|----:|-------|
| **Initiator send, claim → written** | **30.2** | 23.3 | 93.8 | |
| &nbsp;&nbsp;encode (claim → commit) | 1.0 | 0.8 | 3.9 | application fields |
| &nbsp;&nbsp;prepare (SendingTime, BodyLength, CheckSum) | 1.6 | 0.9 | 12.1 | |
| &nbsp;&nbsp;queue (ring → event loop) | 1.0 | 0.6 | 5.9 | another thread drains the ring |
| &nbsp;&nbsp;persist (seq stamp, outbound log) | 0.4 | 0.3 | 1.3 | 3.0 mean with persistence |
| &nbsp;&nbsp;**socket write** | **26.2** | 20.4 | 81.2 | Windows loopback `send()` |
| **Acceptor receive, read → listener** | 4.6 | | | frame 0.4, parse 1.9, session 2.2 |
| **Acceptor listener, up to the ack being prepared** | ~5.6 | | | listener total is 9.2 mean (p50 5.9, p99 118.5); the last ~3.5 of it is the ring commit + `selector.wakeup()`, counted in the next row. Includes ack encode 1.6 and prepare 0.9 |
| **Acceptor ack, prepared → written** | 23.6 | | | queue 5.2 (of which commit + wakeup 3.5), persist 0.3, **socket write 18.2** |
| **Initiator receive, read → done** | 6.2 | 4.9 | 18.8 | frame 0.4, parse 2.1, session 2.3, callback 1.3 |
| Not stamped: kernel delivery, `selectNow` and `read()` before `onDataReceived`, both directions | ~9 | | | the remainder |
| **Round trip (actual send)** | **79.4** | 64.0 | 254.2 | |

`send.commit_call` is the time the committing thread spends in the ring commit plus
`selector.wakeup()`: 6.0 µs mean on the initiator's sending thread and 3.5 µs on the acceptor,
where it runs on the event loop inside the listener, so it is on the critical path.

What this means for Phase 1 on this machine:

1. **Socket writes are about 44 of the 79 µs** (26 + 18). Windows loopback runs the whole TCP
   path inside the sender's `send()` call. This is OS cost; an engine change cannot remove it, and
   AWS/Linux will split differently. The stamped engine time other than the writes is about 26 µs
   per round trip across both sides (70 stamped − 44 written).
2. **`selector.wakeup()` per commit**: about 3.5 µs per message on the acceptor's critical path,
   plus about 6 µs of sending-thread time on the initiator. With both loops busy-spinning it is
   pure overhead. This is the clearest engine-side win (Phase 1 item 1).
3. **Acceptor listener tail.** `recv.callback` p99 is about 118 µs on the acceptor against 3.4 µs
   on the initiator, in every run, while the commit inside it is only 12.9 µs at p99. *Found and
   fixed since:* a log line on every 100th message (see the current baseline above).
4. **Parsing in situ is 1.9–2.1 µs per message**, against 0.27 µs for `wrap` in JMH, so caches are
   cold between messages at 1,000/s. The receive-side session work (2.2 µs, rising to 3.5–4.7 µs
   with persistence) is about the same size as the parse.
5. **Persistence** adds about 7 µs mean in total: outbound log 2.6 µs (initiator) and 1.5 µs
   (acceptor), inbound log 1.2 µs and 2.5 µs. Moving it off the event loop (Phase 1) recovers about
   that on Windows. On AWS persistence costs about 50 µs at p50, so add stage timing to the AWS run
   to see why.

## JMH (ns/op, 2 forks × 5 × 1 s, `-prof gc`)

| Benchmark | Score | ± | Allocation (B/op) |
|-----------|------:|--:|------------------:|
| `OutgoingEncodeBenchmark.wrapHeader` (D) | 14.8 | 0.5 | 0 |
| `OutgoingEncodeBenchmark.wrapHeader` (AE) | 16.5 | 0.8 | 0 |
| `OutgoingEncodeBenchmark.encodeNewOrder` (D) | 175.9 | 7.7 | 0 |
| `OutgoingEncodeBenchmark.encodeNewOrder` (AE) | 174.3 | 4.6 | 0 |
| `SendingTimeBenchmark.sameMillisecond` | 39.2 | 0.4 | 0 |
| `SendingTimeBenchmark.advancingMillisecond` | 45.3 | 6.1 | **96** |
| `FixReaderBenchmark.parseSingle` (per message) | 496.3 | 8.7 | 0 |
| `FixReaderBenchmark.parseBatch` (10 per read, per message) | 483.3 | 13.0 | 0 |
| `IncomingFieldAccessBenchmark.wrapOnly` | 266.1 | 11.4 | 0 |
| `IncomingFieldAccessBenchmark.wrapAndReadListenerFields` | 301.9 | 17.8 | **72** |
| `IncomingFieldAccessBenchmark.wrapAndReadAll` | 510.9 | 10.2 | **528** |
| `IncomingFieldAccessBenchmark.numericFields` | 116.4 | 2.7 | **360** |

- **Encode** allocates nothing. The 2-character MsgType template costs the same as the
  1-character one.
- **SendingTime** uses `Instant` / `ZonedDateTime` in `encodeSendingTime`, which allocates
  96 B/op when the millisecond changes between calls. When the input stays the same, escape
  analysis removes the allocation (`sameMillisecond`); inlined into `encodeNewOrder` it was removed
  too. So whether it allocates depends on what the JIT inlines, which is the Phase 1 item.
- **`FixReader`** costs about 490 ns per message, with no allocation:
  - framing and checksum ≈ 230 ns, field indexing (`wrap`) ≈ 270 ns;
  - batching 10 messages per read barely changes the cost per message.
- **Field access allocates:**
  - `getMsgType()` returns a new `String` each call (most of the listener path's 72 B);
  - `getDouble()` goes through `toString()` + `Double.parseDouble`;
  - reading every body field of an ExecutionReport allocates 528 B per message.
  - `numericFields` overstates its own number, because it never re-wraps the message, so the
    CharSequence pool runs out; the `wrapAnd…` rows are the realistic per-message figures.

## AWS for comparison (from `docs/latency-history.jsonl`, c5.xlarge, 100k at 1,000/s, both sides busy-spun and pinned)

| Commit | Persistence | FIX p50 | p90 | p99 | max | TCP p50 (sockperf) |
|--------|-------------|--------:|----:|----:|----:|-------------------:|
| `f9996c4` | off | 105.8 | 138.5 | 355.6 | 34,105 | 60.0 |
| `3751117` | on | 157.2 | 298.7 | 385.4 | 36,933 | 56.0 |

Those runs used the old sleep-based pacer and are measured from the actual send time, so compare
them with the "actual" column above. The next AWS runs should record the intended-send column and,
with stage timing, both sides' stage tables. The hand-over note in the shared session notes lists
the lines to parse.

## Phase 1, items 3 and 4 on AWS (`ec21814`, c5.xlarge, persistence on, 20k warmup + 100k at 1,000/s), µs

Item 4 isolates the event-loop cpu (3) and its hyperthread sibling (1) on both trading hosts at
boot (`isolcpus`, `nohz_full`, `rcu_nocbs`, IRQs to cpus 0 and 2, irqbalance off;
`scripts/deploy/latency-host-tuning.sh`). Both runs on the same stack: first as provisioned
(isolated), then after `latency-host-tuning.sh remove` and a reboot. RTT from intended send,
except the "actual" rows:

| Run | min | p50 | p90 | p99 | p99.9 | p99.99 | max | TCP p50 | FIX − TCP p50 (actual) |
|-----|----:|----:|----:|----:|------:|-------:|----:|--------:|------:|
| isolated | 65.0 | 75.8 | 86.9 | 137.2 | 252 | 2,331 | 4,207 | 60.2 | 15.3 |
| isolated, actual | 64.9 | 75.5 | 86.5 | 136.7 | 241 | 368 | 1,415 | | |
| not isolated | 65.0 | 77.4 | 87.9 | 132.7 | 249 | 2,718 | 6,672 | 58.9 | 18.2 |
| not isolated, actual | 64.8 | 77.2 | 87.5 | 132.1 | 233 | 297 | 995 | | |
| `b1e881e` (before item 3), other stack | 66.9 | 82.3 | 96.6 | 135.4 | 205 | 409 | 1,873 | 57.9 | 24.2 |

- **Isolation did what it should on the cores**: device IRQs on cpu 3 2.1 → 0/s (initiator), timer
  ticks ~995 → 1/s (nohz_full), involuntary context switches 4.0 → 1.0/s (initiator) and 2.6 → 0
  (simulator); every host check PASS, so `--require-isolation` holds.
- **Latency: p50 −1.7 µs, the rest within run-to-run noise.** With nothing else left on the cores
  at 1,000 msg/s, a timer tick per ms and a few preemptions per second barely reach p99.9.
- **The p99.99 and max from intended send are the sender, not the event loops:** send slip
  p99.99 is 2.1 ms (isolated) and 2.5 ms (not); from the actual send p99.99 is 0.3–0.4 ms. The
  pacer thread is unpinned, and with isolation it shares cpus 0 and 2 with everything else on the
  initiator host (GC, Chronicle, the host check). Pinning the pacer to its own core is the next
  step for the intended-send tail.
- **Item 3 (no allocation) on AWS:** engine time (FIX − TCP p50, actual) 24.2 µs at `b1e881e`,
  18.2 µs not isolated and 15.3 µs isolated at `ec21814`. Different stacks for `b1e881e`, so
  roughly −6 µs from item 3. The exchange simulator's own order handler was not measured for allocation (only
  the engine paths and the sample acceptor were changed).

## AWS: simulator without Aeron, pinned sender (`028bdef`, one stack, persistence on, 20k + 100k at 1,000/s), µs

Both options were removed again after these runs (no latency gain at p50; the scripts no
longer have `--sender-cpu` or `--simulator-store`, `028bdef` has them). All three runs on one
stack, event-loop cpus isolated as provisioned. "Chronicle only": the
simulator stores to its local Chronicle queue (same path) without publishing each message to the
Aeron remote store (`--simulator-store chronicle`). "Pinned sender": the initiator's sending
(pacer) thread pinned to cpu 2, isolated too (`latency-host-tuning.sh apply 3 2`, housekeeping on
cpu 0 only; `--sender-cpu 2`).

| Run | Column | min | p50 | p90 | p99 | p99.9 | p99.99 | max | TCP p50 |
|-----|--------|----:|----:|----:|----:|------:|-------:|----:|--------:|
| baseline (Aeron, sender unpinned) | intended | 64.7 | 78.7 | 96.1 | 147.2 | 285 | 2,822 | 2,961 | 65.8 |
| | actual | 64.4 | 78.5 | 95.9 | 146.3 | 228 | 385 | 966 | |
| | send slip | | 0.21 | | 0.82 | 6.3 | 2,699 | 2,783 | |
| Chronicle only | intended | 63.8 | 79.1 | 95.7 | 141.8 | 1,101 | 3,992 | 6,226 | 62.4 |
| | actual | 63.8 | 78.9 | 95.4 | 139.8 | 228 | 379 | 2,861 | |
| | send slip | | 0.16 | | 0.87 | 870 | 3,844 | 5,988 | |
| pinned sender | intended | 65.4 | 81.3 | 99.2 | 143.0 | 222 | 385 | 1,758 | 61.1 |
| | actual | 65.2 | 81.1 | 99.0 | 142.7 | 222 | 384 | 1,758 | |
| | send slip | | 0.21 | | 0.76 | 2.1 | 6.9 | 307 | |

- **Dropping the Aeron publish changes nothing measurable.** From the actual send the two runs
  match to within 0.5 µs up to p99.99 (p50 78.9 vs 78.5, p99.9 228 vs 228); the publish is an
  encode and a copy into the publication buffer, and the media driver's network send is off the
  event loop. Its intended-send tail is the unpinned sender again (slip p99.9 870 µs in that run).
- **Pinning the sender removes the intended-send tail:** send slip p99.99 2,699 → 6.9 µs, max
  2,783 → 307 µs; RTT from intended send p99.99 2,822 → 385 µs and max 2,961 → 1,758 µs, so
  intended and actual now agree (p99.99 385 vs 384). The remaining max (1.8 ms) is on the round
  trip itself.
- **p50 +2.6 µs with the pinned sender** (actual 81.1 vs 78.5). Within stack noise so far, but a
  candidate cause: cpu 2 is the hyperthread sibling of cpu 0, which now carries all housekeeping
  (GC, JIT, Chronicle, admin, the host check), and the order is encoded on the sender thread.
- On a 4-vCPU host, the sender pinned to its own isolated cpu leaves one cpu for everything else;
  a larger instance would give the sender a whole physical core.

## AWS: stage breakdown, ENA interrupt moderation, busy polling (`316316e`, one stack, persistence on, isolated), µs

Five runs on one stack in this order. The NIC settings were changed live on both trading hosts and
are not in `options`; the history lines carry them in `note`.

| Run | FIX p50 (actual) | p90 | p99 | p99.9 | p99.99 | max | TCP p50 | FIX − TCP |
|-----|----:|----:|----:|------:|-------:|----:|--------:|----:|
| stage timing on, NIC defaults | 64.2 | 73.2 | 123.1 | 189 | 435 | 1,016 | 52.1 | 12.1 |
| NIC defaults | 87.5 | 274.7 | 292.6 | 319 | 382 | 1,833 | 51.4 | (bimodal) |
| ENA `adaptive-rx off`, `rx-usecs 0` | 65.2 | 73.1 | 108.7 | 143 | 248 | 1,715 | 50.2 | 15.0 |
| + `busy_read`/`busy_poll` = 50 | 63.9 | 71.7 | 112.1 | 152 | 244 | 1,116 | 38.1 | (25.8) |
| NIC defaults again | 63.6 | 72.1 | 121.1 | 162 | 250 | 649 | 50.6 | 13.0 |

- **This stack's network was faster** (TCP p50 ~51 µs, against 58–66 on the earlier stacks), so
  compare within the table.
- **Interrupt moderation:** ENA's defaults here are adaptive RX on with `rx-usecs 0`. Turning
  adaptive off changed nothing at p50 (65.2 vs 63.6 µs) and gave p99 −12 µs and p99.9 −19 µs against
  the final baseline, within what separates runs. Not worth a setting.
- **Busy polling:** `net.core.busy_read`/`busy_poll` cut sockperf's TCP round trip by 12.5 µs
  (50.6 → 38.1; sockperf blocks in its receive, so polling the NIC queue replaces the interrupt
  wake-up) but not the FIX round trip (63.9). The engine already busy-spins on non-blocking reads,
  so it gets nothing from a kernel that would otherwise wake it, and FIX − TCP stops meaning
  engine time in that run.
- **The bimodal run** (p90 275 µs, a second mode ~210 µs above the first) matches run 2 on
  `6d856d7`; the repeat at the end, with the same settings, was clean. Cause not found: the
  host checks were clean in both, and moderation was not it (the repeat had it on).

Stage timing, p50 µs (means are close; `--stages` adds a few µs to the round trip):

| Side | send: encode | prepare | queue | persist | write | recv: frame | parse | session | callback |
|------|-----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|
| initiator (order out, ack in) | 0.61 | 0.33 | 0.50 | 0.86 | 3.47 | 0.40 | 0.66 | 0.60 | 0.23 |
| simulator (order in, ack out) | 0.75 | 0.32 | 4.22 | 0.73 | 2.89 | 0.31 | 0.63 | 0.73 | 3.12 |

- **Socket writes are the largest fixed cost**: 3.5 µs (initiator) and 2.9 µs (simulator) per
  message, the `write()` syscall through the TCP stack.
- **Simulator `send.queue` 4.2 µs:** the ExecutionReport is committed to the ring inside the order
  handler on the event loop and waits there until the loop has finished the callback and its
  iteration and drains the ring. A message committed on the loop thread could be written
  directly instead.
- **Simulator `recv.callback` 3.1 µs:** the exchange simulator's order handler (order book, fill
  rules, building the ack).
- Persistence: 0.7–0.9 µs per message per side on send, and part of `recv.session` (0.6–0.7).

## Write on loop-thread commit (`87e2c1b`, `network.write-on-loop-commit`), µs

A message committed on the event-loop thread is written before `commit()` returns instead of
after the callback and the rest of the loop iteration. On the measured path that is the
simulator's ExecutionReports (the initiator's orders are committed on its sending thread).

**Local** (old jar `316316e` B, new A, alternately; RTT from intended send):

| Run | min | p50 | p90 | p99 | p99.9 | p99.99 | max |
|-----|----:|----:|----:|----:|------:|-------:|----:|
| busy-spin, pair 1, before | 21.0 | 32.7 | 64.6 | 124.9 | 254 | 1,521 | 3,301 |
| busy-spin, pair 1, after | 19.7 | 32.1 | 63.1 | 124.5 | 240 | 739 | 2,374 |
| busy-spin, pair 2, before | 20.3 | 33.0 | 66.1 | 130.6 | 302 | 2,173 | 5,497 |
| busy-spin, pair 2, after | 21.8 | 34.5 | 71.1 | 134.0 | 236 | 842 | 1,887 |
| no busy-spin, after | 47.8 | 71.4 | 145.2 | 228.4 | 354 | 549 | 2,173 |
| no busy-spin, before | 48.4 | 72.1 | 146.3 | 232.3 | 356 | 553 | 2,605 |
| stages, before | 20.9 | 33.5 | 65.4 | 131.0 | 293 | 1,667 | 3,281 |
| stages, after | 20.7 | 33.0 | 65.1 | 127.4 | 217 | 706 | 2,804 |

The sample acceptor's `send.queue` fell from 0.90 to 0.10 µs p50 (its listener is light, so the
wait was short); p50 within noise, p99.99 lower in all three busy-spin pairs.

**AWS** (one stack, persistence on, isolated; the switch set to false in the simulator's conf for
the "off" runs; RTT from actual send):

| Run | min | p50 | p90 | p99 | p99.9 | p99.99 | max | TCP p50 | FIX − TCP |
|-----|----:|----:|----:|----:|------:|-------:|----:|--------:|----:|
| stages, on | 62.1 | 76.2 | 90.2 | 133.4 | 218 | 626 | 1,706 | 61.7 | 14.5 |
| stages, off | 61.4 | 77.1 | 92.0 | 134.5 | 215 | 332 | 1,748 | 62.0 | 15.1 |
| on | 59.1 | 72.8 | 86.5 | 127.2 | 205 | 325 | 1,937 | 59.9 | 12.9 |
| off | 61.7 | 74.6 | 88.3 | 134.1 | 210 | 334 | 1,256 | 58.5 | 16.0 |

Simulator stages, p50: `send.queue` **4.35 → 0.04**, `send.total` (claim → written) 9.24 → 5.40;
`recv.callback` 4.14 → 13.29, because the order handler now contains the writes (the simulator
sends two ExecutionReports per order, the ack and a fill, so both writes move into it).

- **The ack leaves the simulator 3.8 µs sooner** (claim → written), as planned.
- **End to end the gain is 0.9–1.7 µs at p50**, and FIX − TCP p50 is 0.6 µs lower in the stages
  pair (14.5 vs 15.1) and 3.1 µs in the headline pair (12.9 vs 16.0); the sockperf p50 itself
  moved 0.3–1.4 µs between runs, so only the direction is firm. (This table first listed the
  sockperf minimum as TCP p50, which gave FIX − TCP of 27–28 µs; corrected.)
  p90 −1.8 to −1.9 µs and p99 −1 to −7 µs, both in the same direction in both pairs.
- The tails do not agree: p99.99 626 vs 332 µs in the stages pair (worse with it on), 325 vs 334
  in the headline pair; max 1,937 vs 1,256 µs in the headline pair. Single runs, so within
  run-to-run variation as far as this shows.
- From the next commit on, latency orders use `Symbol=NOFILL` and the simulator sends only the
  ack (no fill), so its `recv.callback` holds one write; runs before that sent ack + fill.
- Kept on by default: the mechanism is confirmed by the stages, p50 to p99 moved the right way
  in both pairs, and a burst benefits too (each ack goes out behind its own order, not after
  the whole read).

## Ack only, no fill (`96d7d71`, `Symbol=NOFILL`, new stack, persistence on, isolated), µs

The latency orders now match the simulator's `NOFILL*` rule, so it sends the ack and no fill.
Against the last headline run with ack + fill (`87e2c1b`, write-on-loop-commit on, another stack):

| Run | min | p50 | p90 | p99 | p99.9 | p99.99 | max | TCP p50 | FIX − TCP |
|-----|----:|----:|----:|----:|------:|-------:|----:|--------:|----:|
| ack + fill (`87e2c1b`), intended | 59.5 | 73.0 | 86.7 | 127.6 | 217 | 3,129 | 5,976 | 59.9 | 12.9 |
| ack only (`96d7d71`), intended | 57.0 | 65.5 | 74.0 | 115.4 | 159 | 1,583 | 6,066 | 54.5 | 10.9 |

FIX − TCP from the actual-send p50 (72.8 and 65.3). This stack's network was 5.5 µs faster, so most
of the 7.5 µs p50 drop is the network; the engines' share fell about 2 µs, and p90/p99/p99.9 fell
12.7/12.2/58 µs. Without the fill the simulator's loop is free sooner after each ack, which fits the
lower p90 to p99.9; one run, so the size of that gain is not firm.

## Simulator order path: allocation per order (`SimulatorAllocationTest`, local)

The exchange simulator's `handleNewOrder` + ack, measured like the sample acceptor
(`ThreadMXBean` allocated bytes over 20k orders after 30k warm-up, Symbol NOFILL):

| | bytes per order |
|---|---:|
| before | 1,076 |
| after (`Order` constructor, symbol cache, no `Matcher`, shared no-fill decision, numeric OrderID and prefixed ExecID, per-session ClOrdID maps, guarded debug logs, no `AtomicReference`, `OrderType` table) | 312 |

The 312 B are the retained order: `Order` 112, ClOrdID `String` 24 + 32 + the 32 B copy
`String`'s constructor makes from the off-heap bytes, `Long` key 16, two `ConcurrentHashMap`
nodes 64. At 1,000 orders/s that is 0.3 MB/s, against 1 MB/s before; the garbage per order is now
the one 32 B copy.

**AWS** (`6558e62`, new stack, persistence on, isolated, 20k + 100k at 1,000/s; RTT from intended send):

| Run | min | p50 | p90 | p99 | p99.9 | p99.99 | max | TCP p50 | FIX − TCP |
|-----|----:|----:|----:|----:|------:|-------:|----:|--------:|----:|
| ack only (`96d7d71`) | 57.0 | 65.5 | 74.0 | 115.4 | 159 | 1,583 | 6,066 | 54.5 | 10.9 |
| + no allocation (`6558e62`) | 60.1 | 68.2 | 76.7 | 125.5 | 307 | 2,896 | 6,259 | 58.2* | 9.8 |

\* The run's own sockperf baseline was bimodal (p25 58.6, p50 62.5, p75 255 µs: half the samples
in a second mode ~200 µs up, the same shape the two bimodal FIX runs had, so that mode is the
host or network, not the engine); two clean sockperf runs right after on the same stack gave p50
59.5 and 57.0, and their mean is used here. FIX − TCP from the actual-send p50 (68.0).

- This stack's network was 3–4 µs slower (sockperf min 48.4 vs 45.1), and the FIX round trip
  moved with it: p50 +2.7, FIX − TCP 10.9 → 9.8 µs, within run-to-run noise, as expected for a
  change that removes garbage rather than work.
- The tails did not improve in this run (p99.9 307 vs 159, p99.99 2,896 vs 1,583 µs from the
  intended send; from the actual send p99.99 324 vs 234 and max 998 vs 1,540). Both engines run
  ZGC, whose cycles are concurrent, so fewer cycles were never going to remove stop-the-world
  pauses; the intended-send tail is the sending thread's slip (send slip p99.99 2.6 ms here, 1.4
  before), which allocation does not touch. GC counts during the run were not captured (the
  simulator is restarted after the run, so its counters were gone); the script should record
  `jcmd PerfCounter.print` from both JVMs before the restart.

## Persistence without allocation (`AcceptorAllocationTest`, `SimulatorAllocationTest` with a store, local)

Before, every persisted message (both directions, both sides) allocated a `byte[]` copy of the
message, a `LogEntry` and its builder and an `Instant`, plus a `computeIfAbsent` lambda in the
Chronicle store; the outbound path also parsed MsgSeqNum from the copy. The store now writes
straight from the session's buffer (`LogStore.write(..., buffer, offset, length)`, one copy into
the queue's memory), and the sessions pass the read buffer or the outgoing ring.

| | bytes per order, no store | with Chronicle store |
|---|---:|---:|
| sample acceptor | 0 | 0 |
| exchange simulator | 312 (the retained order) | 312 |

So with `log-messages = true` the initiator's event loop allocates nothing per message, and the
simulator's only the order it keeps.

**AWS** (`b056403` = `b8c9ab1` + a skill edit, new stack, persistence on, isolated, 20k + 100k at
1,000/s; RTT from intended send):

| Run | min | p50 | p90 | p99 | p99.9 | p99.99 | max | TCP p50 | FIX − TCP |
|-----|----:|----:|----:|----:|------:|-------:|----:|--------:|----:|
| ack only (`96d7d71`) | 57.0 | 65.5 | 74.0 | 115.4 | 159 | 1,583 | 6,066 | 54.5 | 10.9 |
| + no allocation in the simulator (`6558e62`) | 60.1 | 68.2 | 76.7 | 125.5 | 307 | 2,896 | 6,259 | 58.2* | 9.8 |
| + allocation-free persistence (`b8c9ab1`) | 50.7 | 58.3 | 64.7 | 109.3 | 150 | 548 | 4,366 | 50.2* | 7.9 |

\* The run's own sockperf baseline was bimodal again (p25 49.5, p50 51.8, p75 119 µs); two clean
runs right after gave p50 50.3 and 50.0, and their mean is used. FIX − TCP from the actual-send
p50 (58.1).

- **The fastest stack so far** (sockperf min 40.7 µs against 45–48 before), so most of the 10 µs
  p50 drop is the network; FIX − TCP went 9.8 → 7.9 µs. That is more than the persistence stage
  alone was (0.7–0.9 µs per side), so part of it is run-to-run noise. The three stacks since the
  ack-only change give FIX − TCP 10.9, 9.8, 7.9 for engine changes that only removed garbage.
- **Tails improved for the first time**: p99.9 307 → 150, p99.99 2,896 → 548 µs (intended send),
  actual-send max 998 → 590 µs; send slip p99.99 2,600 → 303 µs. With no garbage on either event
  loop there is nothing for ZGC to do during the run, but one run on a new stack cannot separate
  that from the stack itself.
- `--require-isolation` exited 1 again on the initiator's 1/s preemption WARN only.

## AWS: NIC tuning as a script option (`0d0feb4`, one stack, persistence on, isolated), µs

`--nic-tuning` now sets ENA `adaptive-rx off`, `rx-usecs 0` and `net.core.busy_read = busy_poll = 50`
on both trading hosts for the whole run (sockperf included) and restores them afterwards; it is
recorded as `options.nic_tuning`. Three runs on one stack, in this order, 20k + 100k at 1,000/s; FIX
from the intended send:

| Run | FIX p50 | p90 | p99 | p99.9 | p99.99 | max | TCP p50 | TCP p25 / p75 |
|-----|----:|----:|----:|------:|-------:|----:|--------:|----:|
| NIC tuning on | 72.6 | 92.5 | 156.8 | 334 | 1,935 | 3,916 | 48.1 | 45.5 / 53.8 |
| NIC defaults | 87.4 | 170.2 | 300.0 | 1,235 | 3,959 | 5,964 | 62.1 | 58.6 / 68.5 |
| NIC defaults again | 73.3 | 91.2 | 149.6 | 302 | 1,333 | 4,960 | 61.5 | 58.4 / 67.5 |

- **Same result as the live experiment on `316316e`**: busy polling takes 13 µs off sockperf's
  blocking round trip (61.5 → 48.1) and nothing off the FIX round trip (73.3 → 72.6 p50, p99
  150 → 157). The engine already busy-spins on non-blocking reads, so a kernel that polls the NIC
  queue instead of waking it has nothing to give. FIX − TCP is not engine time in a busy-polling
  run (24.5 here against 11.8 with defaults).
- **A slow stack**: sockperf p50 61.5 with defaults (the `b056403` stack had 50.3), so the FIX p50
  of 73 is the network, not a regression: FIX − TCP with defaults is 11.8, in the 8–13 range of
  the earlier stacks.
- **The middle run was bimodal** (FIX p90 170 µs, a second mode ~90 µs above the first, and the
  sender's slip p99.9 938 µs), with clean host checks; the repeat with the same settings was
  clean. Fourth time this appears on a run, never twice in a row, never traced to a setting.
- Host checks: all PASS on the tuned run; the two defaults runs had the usual initiator
  preemption WARN (both round to 1.0/s, as does the tuned run's PASS), which is all that made
  `--require-isolation` exit 1 on them.

The NIC settings stay off by default; the flag is there for repeating the experiment.

## Sender on the event loop; simulator handler trims (local, Windows, `--stages`, 5k + 20k at 1,000/s), µs

`--sender loop` (default now) runs the latency runner's paced sender as the event loop's loop
task (`NetworkEventLoop.setLoopTask`): each busy-spin iteration sends the next order once it is
due, so schedule, encode and socket write are on one thread and nothing is handed off.
`--sender main` is the older form. The simulator's order handler lost a `containsKey` before its
`put`, a hash of the session id per order (the last session's ClOrdID map is remembered), a
`Random.nextDouble()` for a fill probability of 0, a varargs `log.debug` and two locked counter
adds. Two simulator jars (before `0d0feb4`, after), each with both senders, same JVM:

| Simulator | Sender | RTT p50 (intended) | p90 | p99 | p99.9 | max | slip p99.99 | sim `recv.callback` p50 | callback − send stages |
|-----------|--------|----:|----:|----:|------:|----:|----:|----:|----:|
| before | main | 80.3 | 179.8 | 374.5 | 6,980 | 16,646 | 184 | 48.2 | 3.5 |
| before | loop | 80.8 | 157.6 | 310.5 | 2,363 | 5,059 | 180 | 48.1 | 3.4 |
| after | main | 78.2 | 151.8 | 297.5 | 2,693 | 5,202 | 158 | 47.0 | 3.4 |
| after | loop | 81.7 | 152.3 | 295.7 | 1,562 | 3,881 | 169 | 48.3 | 3.4 |

- **Windows cannot resolve either change.** The simulator's Chronicle write takes 24 µs p50 here
  and the socket write 18 µs (0.8 and 2.9 on AWS), so `recv.callback` is ~48 µs of which the
  handler's own work (callback minus encode, prepare, persist, write) is 3.4–3.5 µs in all four
  runs: the trims are below this machine's noise. The sender's slip tail is ~180 µs with both
  senders (the Windows scheduling quantum), so the main thread's millisecond slip seen on AWS does
  not occur here to be fixed.
- **The loop sender's intended-send tails were lower in both pairs** (p99.9 6,980 → 2,363 and
  2,693 → 1,562; max 16.6 → 5.1 ms and 5.2 → 3.9 ms), but each loop run followed the main run on
  the same simulator, so a warmer simulator is as likely a cause. p50 moved by −0.5 to +3.5 µs,
  within run-to-run noise here.
- Both changes are to be judged on AWS: `--sender main` against the default on one stack, and the
  simulator's `recv.callback` minus its send stages with `--stages`.

## AWS: sender on the event loop (`7b916f7`, one stack, persistence on, isolated), µs

Two runs on one stack, 20k + 100k at 1,000/s, the loop sender (`98c75d9`, the default) first and
`--sender main` as the control right after; both with the simulator handler trims (`6ab4bbc`).
RTT from the intended send:

| Sender | min | p50 | p90 | p99 | p99.9 | p99.99 | max | slip p99.9 / p99.99 / max | TCP p50 | FIX − TCP |
|--------|----:|----:|----:|----:|------:|-------:|----:|----:|--------:|----:|
| loop | 58.7 | 66.5 | 74.7 | 121.3 | 180 | 276 | 2,122 | 0.67 / 1.3 / 81 | 56.0 | 10.5 |
| main (control) | 60.6 | 69.4 | 77.3 | 121.3 | 158 | 1,483 | 5,362 | 1.9 / 1,278 / 5,272 | 56.1* | 13.4 |

\* The control's sockperf run was bimodal (p75 61, p90 240); its p25/p50 match the first run's.

- **The sender's tail is gone.** Send slip p99.99 1,278 → 1.3 µs and max 5,272 → 81 µs: the
  loop sends an order within one busy-spin iteration of its due time, and there is no second
  thread to be descheduled. RTT from the intended send now agrees with RTT from the actual send
  (p99.99 276 vs 276, max 2,122 both), where the control shows the familiar gap (1,483 vs 245,
  5,362 vs 1,118). The 2.1 ms max that remains is on the round trip itself.
- **p50 −2.9 µs** (69.4 → 66.5; min −1.9) with the same network (sockperf p50 56.0 / 56.1): the
  claim, encode and write now run on the pinned, isolated core instead of the main thread's,
  and the ring hand-off to the loop is gone. FIX − TCP 13.4 → 10.5.
- **p99.9 is the one number the control wins** (158 vs 180 µs): one run each, and the loop run's
  p99 is identical (121.3), so it is noise until repeated.
- The simulator handler trims cannot be separated here (both runs have them); the handler's own
  work was ~2 µs before, and the AWS FIX − TCP of 10.5 is in line with the 8–13 of earlier stacks.
- Host checks all PASS on both runs, including the initiator's preemption (1.0/s, rounding to the
  threshold), so `--require-isolation` exited 0 for the first time.

## AWS: instance generation and core layout (network check), µs

The engines' share of the round trip is ~10.5 µs; the other ~56 is the network between two
c5.xlarge hosts in one cluster placement group (`aws_placement_group.trading`, already in place,
so placement is not the cause of the 48–62 µs sockperf spread between stacks). ENA Express was
ruled out without a run: on the x86 compute families it needs 8xlarge and up, and AWS's own
guide says median latency during light traffic *rises* by tens of µs (it targets tail latency
under congestion and single-flow bandwidth). What is left to vary is the host and NIC
generation and whether a vCPU is a hyperthread or a core. Two stacks, one run each with the
defaults (loop sender, persistence on, isolated, 20k + 100k at 1,000/s), code `410bf11`:

| Type | vCPU / cores | Sustained clock | NIC | What it isolates |
|------|------|------|------|------|
| c5.xlarge (today) | 4 / 2 | 3.4 GHz Skylake 8124M | up to 10 Gb, Nitro v3 | baseline: 56–62 µs TCP p50 on the last four stacks |
| c6in.xlarge | 4 / 2 | 3.5 GHz Ice Lake 8375C | up to 30 Gb, newest ENA | the network path and host generation, same core layout |
| c7a.2xlarge | 8 / 8, no SMT | 3.7 GHz Zen 4 EPYC 9R14 | up to 12.5 Gb | a whole core per thread, sender and housekeeping on their own cores |

**Expectations, written before the runs.** sockperf (the network alone) is the number to watch:
c6in should take it down if the Nitro card and host matter (anything under ~50 would be new; the
best c5 stack gave 50.3); c7a's network is one generation newer than c5 but not an "n" type, so
its sockperf may not move, while FIX − TCP should fall below 10 (the engine's syscalls and
copies on a faster, unshared core) and the tails (p99.9, p99.99, max) should improve with no
sibling and the sender on its own core. Per-core speed alone is worth single-digit percent of
the engine's 10 µs — a few µs at most; a move in the network number would be worth more than
everything engine-side that remains.

**Results** (one run each, RTT from the intended send; the c5 row is the last c5 stack, `7b916f7`,
same code except the debug guards, and the c5 range is the four stacks since the ack-only change):

| Type | TCP p50 (p25 / p75) | FIX min | p50 | p90 | p99 | p99.9 | p99.99 | max | FIX − TCP |
|------|------:|----:|----:|----:|----:|------:|-------:|----:|----:|
| c5.xlarge (last stack) | 56.0 (53.9 / 58.7) | 58.7 | 66.5 | 74.7 | 121.3 | 180 | 276 | 2,122 | 10.5 |
| c5.xlarge (four stacks) | 48–62 | 50.7–62.2 | 58.3–73.3 | | 109–157 | 150–334 | 276–1,935 | 2,122–4,960 | 7.9–13.4 |
| **c6in.xlarge** | 54.8 (51.2 / 57.3) | 50.7 | **58.7** | 65.3 | **90.1** | **122** | **193** | **521** | **3.9** |
| c7a.2xlarge | 54.4 (53.1 / 56.0) | 56.4 | 63.9 | 73.0 | 100.4 | 130 | 322 | 633 | 9.5 |

- **The network did not move**: sockperf p50 54.4–54.8 on both, inside the c5 range, with no
  bimodal second mode on either (p75 57.3 / 56.0 against 119–255 on the bimodal c5 runs). The
  raw path between two hosts in the placement group is ~54 µs whatever the generation; the
  expectation that the "n" NIC would lower it was wrong.
- **c6in.xlarge is the best stack measured**: FIX p50 58.7, equal to the best c5 stack
  (`b056403`, which had a 50.3 network), on an ordinary 54.8 network, so FIX − TCP fell to 3.9.
  Part of that is the host (Ice Lake against Skylake for the engine's syscalls and copies), part
  is what sockperf measures: its blocking receive pays an interrupt and a wake-up that the
  busy-spinning engine does not, so the gap is engine time minus sockperf's own overhead, and a
  host with a faster wake-up path shows a smaller gap. One run; what is not in doubt is the
  round trip itself and its tails.
- **Tails are where the generations differ most**: p99.9 122 / 130 against 150–334 on c5,
  p99.99 193 / 322 against 276–1,935, max 521 / 633 µs against 2.1–5.0 ms. Every c5 stack had a
  millisecond max; neither newer host does. Nothing in the code changed between these runs.
- **c7a.2xlarge did not beat c6in** despite the full cores and the 3.7 GHz clock: p50 63.9,
  FIX − TCP 9.5. Its initiator still shows the 1/s preemption WARN with cpu 3 isolated alone
  (so the WARN is not the hyperthread sibling), and its tails are second best. Whole cores did
  not matter at 1,000 msg/s; the Nitro/host generation of the "n" type did.
- Price (us-east-1 on-demand, per host): c5.xlarge $0.17/h, c6in.xlarge $0.227/h,
  c7a.2xlarge $0.41/h. c6in.xlarge is now the default `trading_instance_type` (`terraform.tfvars`,
  `variables.tf`); history lines before this are c5.xlarge unless `instance_type` says otherwise.
  The skill's `--instance-type` still selects per run.

## AWS: c6in.2xlarge, ENA LLQ and NUMA (`559a939`, code `410bf11`, one stack, persistence on, isolated), µs

Same code and options as the network check above (20k warmup + 100k at 1,000/s, simulator tuned,
loop sender), trading hosts `c6in.2xlarge` (8 vCPU / 4 cores, same Ice Lake 8375C and ENA as the
xlarge, 8 NIC queues instead of 4, $0.45/h per host). Before the run both hosts were inspected for
the ENA low-latency queue and for NUMA.

**ENA LLQ is already on.** The in-tree `ena` driver (kernel 6.8.0-1066-aws) has no ethtool
private flag for it (`--show-priv-flags`: not supported), but every indicator says device
placement: the per-queue `tx_llq_buffer_copy` counters are non-zero (they count only in LLQ mode),
dmesg has none of the driver's "LLQ is not supported, fallback to host mode" lines, the TX ring
maximum is 1024 (the LLQ limit; RX is 8192) and the device exposes the 1 MB prefetchable BAR
(region 2) that LLQ pushes headers through. There is nothing to turn on; it was on for the c5
and c6in.xlarge runs too.

**NUMA: one node, nothing to place.** One socket, 4 cores × 2 threads, `NUMA node0 CPU(s): 0-7`,
the NIC reports `numa_node -1` / `local_cpulist 0-7` (a virtual function, no locality). The
boot tuning isolated cpu 3 and its sibling 7 and put all nine ENA IRQs on 0-2,4-6; irqbalance
inactive. Both hosts identical.

| Type | TCP p50 (p25 / p75) | FIX min | p50 | p90 | p99 | p99.9 | p99.99 | max | FIX − TCP |
|------|------:|----:|----:|----:|----:|------:|-------:|----:|----:|
| c6in.xlarge (network check) | 54.8 (51.2 / 57.3) | 50.7 | 58.7 | 65.3 | 90.1 | 122 | 193 | 521 | 3.9 |
| **c6in.2xlarge** (stack 1, `559a939`) | 49.1 (48.6 / 50.3) | 39.2 | **46.3** | **50.3** | **69.7** | **85.7** | **98.9** | **451** | **−2.8** |
| c6in.2xlarge (stack 2, `63a822a`, default) | 49.1 (48.7 / 50.4) | 41.1 | 54.7 | 67.3 | 76.0 | 101.6 | 120.8 | 410 | 5.5 |

- **The best run so far by a wide margin**: FIX p50 46.3 against 58.7 on the xlarge and 66.5 on
  the last c5, p99.99 98.9 against 193 and 276, max 451 against 521 and 2,122. Send slip p99.99
  0.74 µs, intended = actual.
- **The network moved this time**: sockperf p50 49.1 with a tight distribution (p25 48.6, p75
  50.3, p99 63.6, max 133), the lowest and narrowest of any stack. Whether that is the 2xlarge's
  larger share of the Nitro card or a lucky placement is one run's worth of evidence; the four
  c5 stacks ranged 48–62 on the same code, so the TCP number alone would not be conclusive. The
  FIX round trip moving 12 µs with it, and the tails halving, is what one stack cannot explain
  by placement alone.
- **FIX is now below sockperf.** The engine's whole round trip (two kernel receives, two sends,
  parse, session, persist on both sides) takes less than sockperf's own ping-pong, so the
  "FIX − TCP = engine time" reading is finished: sockperf's blocking `recv` pays an interrupt,
  a wake-up and a scheduler hop on each side that the pinned, busy-spinning engine does not, and
  on this host that overhead exceeds the engine's work. From here the sockperf number is a check
  that the network is normal (bimodal, slow placement), not a floor to subtract.
- Host checks: all PASS on both except the initiator's usual 1.0/s preemption WARN
  (`--require-isolation` exits 1 on it; the data is valid). Simulator read cap hit 500 times over
  the connection, initiator 0 in the timed phase.
- `trading_instance_type` is now c6in.2xlarge (`terraform.tfvars`, `variables.tf`). The first run
  with the default (stack 2, same code) came back at **FIX p50 54.7** on an identical network
  (sockperf 49.1, p25/p75 48.7/50.4 on both stacks): 8.4 µs above stack 1 with the network
  ruled out as the cause, p90 67.3 against 50.3, p99.99 121 against 99, max 410 against 451. All
  host checks PASS on both hosts (the initiator's preemption rounded to PASS this time). So the
  12 µs between the xlarge and stack 1 was partly the stack: across the two 2xlarge stacks FIX
  is 46–55 on a 49 network, against 58.7 on the one xlarge stack's 55 network. The size still
  looks better than the xlarge, and both 2xlarge stacks beat every earlier run on p99.99 (99,
  121 against 193+) and max (410, 451 against 521+); but the host-side variation between two
  stacks of the same type on the same network, ~8 µs at p50 and 17 at p90, is now the largest
  unexplained term and larger than anything left in the engine. Simulator read-cap hits 812
  against 500; initiator 0 in both.

## AWS: three runs on one c6in.2xlarge stack, with `--stages` (`890b5c6`, code `410bf11`), µs

To separate the JVM from the host in the 8 µs between the two 2xlarge stacks above: one stack
(stack 3), the test three times without re-provisioning. Each run restarts both engines (fresh
JVMs on both sides); runs 1 and 2 are the plain test, run 3 adds `--stages`. Cpu 3's reported
MHz and steal were sampled once a second on both hosts during runs 1 and 2: the guest reports a
constant nominal 2,899.9 MHz and zero steal, so turbo is not observable from inside the instance
and that candidate stays unmeasured.

| Run | TCP p50 (p25 / p75) | FIX p50 | p90 | p99 | p99.9 | p99.99 | max |
|-----|------:|----:|----:|----:|------:|-------:|----:|
| 1 | 49.3 (48.0 / 50.8) | 47.3 | 51.7 | 70.4 | 89.9 | 108 | 382 |
| 2 (4 min later) | 49.1 (48.4 / 49.9) | 47.7 | 51.5 | 70.1 | 88.3 | 122 | 928 |
| 3 (`--stages`, 7 min later) | 48.8 (45.9 / 50.7) | 52.8 | 68.1 | 77.7 | 101.7 | 307 | 876 |
| stack 1 (`559a939`) | 49.1 | 46.3 | 50.3 | 69.7 | 85.7 | 99 | 451 |
| stack 2 (`63a822a`) | 49.1 | 54.7 | 67.3 | 76.0 | 101.6 | 121 | 410 |

**Run 3 stage tables** (p50 / p90 / p99, µs; the simulator's `recv.callback` holds the ack's
encode, prepare, persist and write under write-on-loop-commit):

| Side | send.encode | send.prepare | send.queue | send.persist | send.write | send.total | recv.frame | recv.parse | recv.session | recv.callback | recv.total |
|------|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| initiator | 0.31 / 0.34 / 0.55 | 0.28 / 0.28 / 0.44 | 0.05 / 0.06 / 0.11 | 0.46 / 0.50 / 3.39 | 2.32 / 2.51 / 2.74 | 3.42 / 3.73 / 6.49 | 0.38 / 0.40 / 0.41 | 0.49 / 0.51 / 0.55 | 0.42 / 0.45 / 3.37 | 0.17 / 0.20 / 0.32 | 1.46 / 1.58 / 4.44 |
| simulator | 0.95 / 0.99 / 1.29 | 0.25 / 0.26 / 0.44 | 0.05 / 0.05 / 0.17 | 0.61 / 0.67 / 4.41 | 2.29 / 2.51 / 2.92 | 4.12 / 4.58 / 8.34 | 0.35 / 0.37 / 0.39 | 0.37 / 0.40 / 0.44 | 0.73 / 0.79 / 4.17 | 5.09 / 5.70 / 9.49 | 6.54 / 8.96 / 12.02 |

- **The JVM is cleared.** Runs 1 and 2, fresh JVMs on both sides four minutes apart, agree to
  0.4 µs at p50 and 0.2 at p90. Whatever separates a 47 run from a 55 run is not C2's compile
  of that process.
- **So is "the stack".** Run 3, same hosts seven minutes after run 2, is a 55-class run: p50
  52.8, p90 68.1, the same p50-to-p90 spread of 13–15 µs as stack 2 (`--stages` itself costs
  ~1–2 µs per side, not 5 at p50 and 17 at p90). The slow mode comes and goes over minutes on
  one pair of hosts; it is not a property fixed at placement.
- **The engines are not where it is.** With the slow mode present, both stage tables are flat:
  the initiator's send is 3.4 / 3.7 / 6.5 and its receive 1.5 / 1.6 / 4.4; the simulator's
  receive-plus-ack is 6.5 / 9.0 / 12.0. Summing p50s the engines hold ~11.4 µs of the 52.8
  round trip and ~14.3 of the 68.1 at p90: their p50-to-p90 growth is ~3 µs against 15 for the
  round trip. The other ~12 µs of spread sits between one side's `send.write` returning and the
  other side's read: NIC queues, the Nitro card, the fabric and the receiving kernel. The
  guest-side evidence (sockperf runs before the FIX phase, not during it, and showed nothing)
  cannot say which; a sockperf run concurrent with the FIX phase on a spare core would.
- **What the engines do show**: `send.persist` and `recv.session` (the two store writes) carry
  the p99 on both sides (3.4–4.4 against 0.4–0.7 at p50) and the p99.9 (32–36 µs): the journal
  tail, the lighter-journal target. Socket write is 2.3 µs p50 on both sides, encode 0.3 / 0.95.
- Host checks: all PASS on both hosts in all three runs except the initiator's preemption WARN
  in runs 2 and 3 (1.0/s rounding); steal 0 throughout.

## AWS: Chronicle pretouch (`dabd224`, one c6in.2xlarge stack, three runs), µs

Chronicle Queue maps each journal in 64 MB chunks and writes into a sparse file, so the writer
took a first-touch page fault every 4 KB (one write in ~20 at our message size, 1–3 µs each)
and mapped each new chunk itself; the OSS `ExcerptAppender.pretouch()` is a no-op (the
`PretouchFactoryEmpty` in 5.24). `ChronicleLogStore` now runs a `chronicle-pretouch` daemon
thread that keeps `persistence.pretouch-ahead` (default 1 MB) faulted in past every stream's
write position through the queue's own mapping (`MappedFile.acquireByteStore` and a CAS of 0
for 0 per page, which writes nothing), every `pretouch-interval-ms` (10). `--no-pretouch` on
the AWS script sets it to 0 on both sides. One stack, three runs four minutes apart:

| Run | TCP p50 | FIX p50 | p90 | p99 | p99.9 | p99.99 | max |
|-----|------:|----:|----:|----:|------:|-------:|----:|
| A: pretouch on, `--stages` | 48.7 | 49.7 | 53.2 | 63.4 | 77.9 | 114 | 316 |
| B: `--no-pretouch --stages` (control) | 48.4 | 49.9 | 55.0 | 69.3 | 98.9 | 126 | 1,030 |
| C: pretouch on, plain | 49.1 | 48.9 | 52.4 | 62.3 | 80.8 | 115 | 363 |

**Store stages, p50 / p99 / p99.9** (`send.persist` is the outbound journal write,
`recv.session` holds the inbound one):

| Side, stage | pretouch on (A) | off (B) |
|-------------|----:|----:|
| initiator `send.persist` | 0.48 / 1.49 / 4.85 | 0.44 / 3.38 / 34.3 |
| initiator `recv.session` | 0.43 / 0.65 / 3.18 | 0.39 / 3.34 / 35.4 |
| initiator `send.total` | 3.45 / 4.93 / 10.3 | 3.42 / 6.57 / 37.6 |
| initiator `recv.total` | 1.44 / 1.92 / 4.33 | 1.44 / 4.42 / 36.6 |
| simulator `send.persist` | 0.62 / 1.07 / 11.3 | 0.60 / 5.48 / 42.3 |
| simulator `recv.session` | 0.81 / 3.69 / 12.4 | 0.73 / 5.92 / 40.6 |
| simulator `recv.total` | 7.46 / 13.6 / 23.0 | 7.39 / 15.9 / 52.7 |

- **The page faults were the store tail.** With the pretoucher the two journal writes lose
  their p99 step (3.3–5.9 µs → 0.7–1.5 on the initiator, 1.1–3.7 on the simulator) and their
  p99.9 (34–42 µs → 3–5 on the initiator, 11–12 on the simulator). The p50 is unchanged, as
  expected: Chronicle's own write was never the cost.
- **Round trip**: p99 −6 µs, p99.9 −21, p99.99 −12, max 1,030 → 316, p50 unchanged (49.7 vs
  49.9). The simulator's remaining p99.9 of 11–12 µs on the store stages is its Aeron
  publication plus the local Chronicle cache; the initiator's 3–5 is the kernel's writeback
  on the active page, which neither design removes (an `msync` on the pretouch thread, or the
  host's dirty-page settings, would).
- **Verified running**: the simulator journal logged `Chronicle pretouch started` for its
  streams and `ps` showed the `chronicle-pretouch` thread; the initiator's run log had the
  same line. The thread is a plain daemon, so `isolcpus` keeps it off the event-loop core.
- Run C's send slip (p99.9 7.3 µs, p99.99 37, max 59; 0.35 / 0.8 / 10–92 in A and B) is a
  sender hiccup of ~100 orders out of 100k; one occurrence, not seen in A, worth watching
  for a pretouch-thread interaction with the writer's chunk lock on later runs.
- Host checks: all PASS on both hosts except the initiator's preemption WARN (1.0/s rounding)
  in run B. Run A's stack had already shown that `--stages` costs ~1 µs at p50 here (A 49.7 vs
  C 48.9).

**Lighter journal, reviewed against this.** The remaining store cost is p50 0.4–0.8 µs per
write and a p99.9 of 3–5 µs (initiator) that is writeback, not Chronicle. A preallocated,
length-prefixed store would save a few hundred ns at p50 and nothing at the tail that an
`msync` thread would not also save; it would add a second on-disk format, its replay and its
tests. Not worth building now; the pretoucher is the lighter journal's gain at a fraction of
its cost. The simulator's 11–12 µs store p99.9 is Aeron's, and belongs to the Aeron path.

## AWS: the network floor without the kernel (DPDK ping-pong, one c6in.2xlarge stack), µs

Before deciding on a userspace transport for the engine, the raw path between the two trading
hosts was measured through DPDK's ENA poll-mode driver, next to sockperf on the kernel path.
`scripts/deploy/dpdk/run-dpdk-floor.sh` attaches a second ENI to each trading host (same subnet
and security group, UDP 11111 allowed), installs Ubuntu's DPDK (21.11.9 on this image, kernel
6.8.0-1066-aws), reserves 1,024 × 2 MB hugepages, binds the second ENI to the kernel's `vfio-pci`
in no-IOMMU mode (no write-combining: the AWS patched module was not used), and runs
`pingpong.c`: a reflector on the FIX host that swaps MACs, IPs and UDP ports, and a paced sender
on the OUCH host that spins for each reply and times it with `rte_rdtsc`. Both run on the
isolated cpu 3, one Rx and one Tx queue, 200-byte UDP payload, 20,000 warmup + 100,000 packets at
1,000/s, like the FIX test. sockperf ran on the primary ENIs, 200 B, 30 s (20 s for the repeat).

| Path | min | p50 | p90 | p99 | p99.9 | p99.99 | max |
|------|----:|----:|----:|----:|------:|-------:|----:|
| sockperf TCP, kernel | | 49.4 | 55.1 | 93.6 | 111 | 146 | 158 (p99.999) |
| sockperf UDP, kernel | | 46.2 | 50.0 | 57.8 | 64.8 | 142 | 171 (p99.999) |
| sockperf UDP, kernel, repeat | | 49.1 | 51.3 | 59.0 | 65.3 | 131 | |
| **DPDK UDP ping-pong, run 1** | 18.5 | **22.6** | 25.1 | 27.6 | 30.2 | 43.1 | 56.1 |
| DPDK UDP ping-pong, run 2 | 18.1 | 22.4 | 25.0 | 27.6 | 30.1 | 43.5 | 99.5 |

- **The kernel path costs about 24 to 27 µs of the 46 to 49 µs round trip.** Without it the
  two hosts exchange a packet in 22.5 µs at p50, with no loss in 200,000 packets. That is the
  Nitro card, the fabric and the poll-mode driver on both sides; the engine's own ~11 µs would
  sit on top of it.
- **The tail is the larger difference**: p99.9 30 against 65–111, p99.99 43 against 131–146,
  max 56–100 against 158–171 µs. With no interrupt, softirq or scheduler on the path, the
  distribution is 18 to 30 µs wide at p99.9.
- Write-combining was not enabled (stock `vfio-pci`), so LLQ ran with uncached PCI writes; the
  AWS README warns this hurts throughput. At one packet a millisecond it did not show; a run
  with the patched module may still be a little faster.
- What this says for the engine: a userspace TCP on DPDK (F-Stack in library mode) could take
  the FIX round trip from ~49 to roughly 30–35 µs at p50 if its own cost stays near the raw
  path's, and would flatten the tails below anything the kernel path has shown. The 15 µs
  threshold set for building the transport is cleared by about 10 µs.

## JDK 25 (`b4aa2c2`, local, Windows, 20k + 100k at 1,000/s, busy-spin, persistence on), µs

The whole build moved to `maven.compiler.release` 25 (Corretto 25 on the hosts, Temurin 25 in
CI) with Chronicle Queue 5.27ea11; `-XX:+UseZGC` is now generational ZGC. Alternating runs of
the previous commit's jar on Microsoft JDK 17 and the new jar on Corretto 25.0.5, same machine:

| Run | JDK | RTT p50 (intended) | p90 | p99 | p99.9 | p99.99 | max | actual p50 |
|-----|-----|----:|----:|----:|------:|-------:|----:|----:|
| 1 | 17 | 114.8 | 222.0 | 1,835 | 28,606 | 84,083 | 92,209 | 113.7 |
| 2 | 25 | 117.3 | 184.3 | 359.7 | 5,124 | 12,386 | 19,988 | 116.4 |
| 3 | 17 | 98.6 | 160.0 | 246.0 | 868 | 7,037 | 12,837 | 97.9 |
| 4 | 25 | 113.7 | 188.9 | 317.2 | 1,062 | 6,787 | 9,667 | 112.8 |

- **No regression this machine can see.** p50 is within the run-to-run spread of the JDK 17 jar
  itself (98.6 and 114.8); the JDK 25 tails were lower in the first pair and equal in the
  second. The JDK's own effect on the engine is measured on AWS (one kernel-path run on Corretto
  25 before the transport A/B).
- Chronicle core 2.24 looks up `sun.nio.ch.UnixFileDispatcherImpl` on any Java 20+ JVM, which
  does not exist on Windows (`FileDispatcherImpl`); 2.27 (with Queue 5.27) checks the OS. The
  AWS hosts were never affected.
- Found on the way: the session tests fail against an acceptor whose `fix-logs/acceptor` store
  holds earlier runs. With `reset-on-logon` every run restarts at sequence 1, and
  `processIncomingResendRequest` replays every stored OUTBOUND entry whose sequence number is in
  range, across all those runs (2.1 GB here: thousands of resends, the tester's ring fills,
  the reconnect times out). The replay should be bounded to the current sequence epoch; until
  then run the session tests on a fresh store.

## Transport seam, kernel path (`f0cf426` against `b4aa2c2`, local, Windows, JDK 25, 20k + 100k at 1,000/s, busy-spin, persistence on), µs

The event loop now reaches its sockets only through `Transport`/`Endpoint`
(`NioTransport`/`NioEndpoint` for the kernel). Alternating runs of the jar before the seam and
the jar with it, same JVM and machine:

| Run | Jar | RTT p50 (intended) | p90 | p99 | p99.9 | p99.99 | max | actual p50 |
|-----|-----|----:|----:|----:|------:|-------:|----:|----:|
| 1 | before | 112.8 | 187.0 | 355.3 | 5,308 | 16,957 | 21,561 | 112.1 |
| 2 | seam | 110.3 | 182.7 | 340.2 | 3,209 | 11,231 | 16,941 | 109.8 |
| 3 | before | 111.0 | 184.3 | 352.3 | 5,472 | 12,485 | 15,221 | 110.5 |
| 4 | seam | 124.5 | 228.2 | 1,098 | 8,098 | 23,478 | 32,784 | 123.7 |

- **No cost visible.** The seam run 2 is the best of the four at every percentile; run 4 is the
  worst at every percentile, with p99 three times the others, which is this machine getting
  busy (the run coincided with other work on it), not the jar. The read and write call sites
  went from `SocketChannel` methods to `Endpoint` methods, monomorphic in a kernel-mode JVM.
- The AWS kernel-path run on this code is the real check (one c6in.2xlarge stack, isolated cores).

## AWS: F-Stack transport against the kernel path (`6f88b15`, one c6in.2xlarge stack, persistence on, isolated, 20k + 100k at 1,000/s), µs

`--transport fstack`: both engines on FreeBSD TCP over DPDK (F-Stack in library mode behind the
transport seam) through a second ENI per host, the initiator's session connecting to the
simulator's second-ENI address; `kernel`: NIO sockets on the primary ENI as before. Same JVM
(Corretto 25, generational ZGC), same cores, sockperf on the kernel path in every run. FIX round
trip from the intended send (CO-corrected):

| Run | Transport | min | p50 | p90 | p99 | p99.9 | p99.99 | max | slip p99 | TCP p50 / p99 |
|-----|-----------|----:|----:|----:|----:|------:|-------:|----:|---------:|--------------:|
| 1 | kernel | 38.9 | 45.8 | 50.0 | 62.1 | 80.5 | 122.9 | 389 | 0.35 | 47.3 / 63.2 |
| 2 | fstack, loop every 100 µs | 36.6 | 91.0 | 130.9 | 140.5 | 145.7 | 186.5 | 568 | 99.1 | 48.9 / 84.6 |
| 3 | fstack | 35.9 | 40.4 | 43.1 | 46.8 | 63.6 | 138.0 | 499 | 0.28 | 46.2 / 62.1 |
| 4 | fstack | 36.0 | **40.0** | 42.6 | **46.7** | 64.4 | 130.8 | 487 | 0.30 | 48.4 / 63.7 |
| 5 | kernel | 38.7 | 45.8 | 49.9 | 61.7 | 78.1 | 111.5 | 1,271 | 0.35 | 47.4 / 62.8 |

- **5.8 µs at p50, 15 µs at p99.** The kernel path reproduces to 0.03 µs between runs 1 and 5
  (45.76 and 45.79), and F-Stack to 0.4 µs between runs 3 and 4 (40.4 and 40.0). The kernel's
  p99 (62) is above its p90 by 12 µs; F-Stack's (46.7) by 4 µs: the p90–p99 band that the
  kernel's receive path, wakeups and softirqs used to fill is gone. p99.9 moves from ~79 to ~64;
  p99.99 and max do not move (~130 and ~490: those are the host, not the stack).
- **Not the 22.5 µs floor.** The DPDK UDP ping-pong between these hosts (previous section) is
  22.5 µs at p50; the FIX round trip on F-Stack is 40.0, so F-Stack's TCP and the engines
  together cost 17.5 µs over the raw packet path, against ~23 µs for the kernel's TCP plus the
  same engines. The engines were measured at ~11 µs on the kernel path (stage timing), which
  leaves about 6–7 µs for F-Stack's TCP per round trip: the FreeBSD stack is not free, and the
  remaining gap to the floor is it, not the kernel.
- **Run 2 is the loop interval.** F-Stack calls the application's loop only every
  `idle_loop_interval` (100 µs by default) while the NIC is idle; the engine's paced sender
  lives in that loop, so sends slipped by a uniform 0–100 µs (slip p50 50, p99 99) and the
  intended-send histogram shows the slip, not the network. `idle_loop_interval = 0` in
  `fstack.ini` (runs 3 and 4) calls it on every pass; from the actual send, run 2 already
  measured 40.4 at p50.
- **What it took to run a JVM on F-Stack** (all in `run-aws-latency-test.sh` and
  `setup-fstack-host.sh`): F-Stack's bundled DPDK 24.11 built on the host, F-Stack compiled
  `-fPIC` with initial-exec TLS, the shim linked with DPDK static (F-Stack patches DPDK's
  timer library without exporting the symbol from the shared one); the shim preloaded into the
  JVM (dlopen'd, its 17 KB of static TLS left the simulator dead with SIGSEGV at address 0 on
  its newest thread), `libjsig` chained, root for vfio in no-IOMMU mode, the loop thread pinned
  by `rte_eal_init` rather than before it (EAL's helper threads otherwise landed on the
  measured core), and the stack never stopped (F-Stack's shutdown double-frees its config; the
  initiator exited 134 until `ob_stop` became a no-op).
- **Host checks.** Affinity, busy-spin (99–100 %), isolation and NIC IRQs PASS on both hosts
  in every run. The initiator reports one involuntary context switch per second with no other
  runnable task on the core in runs 3, 4 and 5 (kernel too), so it is this stack's tick, not
  the transport; `--require-isolation` turns it into exit 1, the numbers stand.
- **Run 2's exit code 134** was the shutdown abort above; its histograms are complete.

## AWS: where F-Stack's time goes (`f8eac02`, one fresh c6in.2xlarge stack, persistence on, isolated, 20k + 100k at 1,000/s), µs

The four measurements that split the F-Stack round trip, in order of what they add: the raw
DPDK UDP ping-pong (`pingpong.c`, no stack), a TCP echo over F-Stack with no JVM
(`fstack-echo.c`: `ff_epoll_wait` / `ff_read` / `ff_write` on the same `fstack.ini`), and the
FIX round trip on F-Stack and on the kernel path, both with `--stages`. Same hosts, same cores,
same second ENIs, 200 B payloads, one message every 1,000 µs, from the intended send.

| Path | min | p50 | p90 | p99 | p99.9 | p99.99 | max | lost | slip p99 |
|------|----:|----:|----:|----:|------:|-------:|----:|-----:|---------:|
| DPDK UDP ping-pong (C) | 18.5 | **22.7** | 25.1 | 27.5 | 29.7 | 32.4 | 137 | 0 | — |
| F-Stack TCP echo (C) | 29.1 | **33.9** | 36.2 | 38.4 | 40.4 | 51.2 | 97 | 0 | 0.21 |
| FIX on F-Stack, `--stages` | 37.5 | **41.4** | 44.1 | 48.5 | 67.3 | 165.4 | 378 | 0 | 0.30 |
| FIX on the kernel, `--stages` | 40.8 | **48.3** | 53.2 | 66.1 | 83.3 | 143.6 | 2,187 | 0 | 0.39 |

(sockperf on the kernel ENI during the two FIX runs: 49.1 / 66.1 and 46.8 / 61.6 at p50 / p99.)

- **The split at p50: 22.7 network + 11.2 F-Stack TCP + 7.5 engines = 41.4.** F-Stack's
  FreeBSD TCP costs 11.2 µs per round trip over the raw packet path (33.9 against 22.7: two
  `tcp_output` and two `tcp_input` traversals, the mbuf copies on the way in and out, the kqueue
  wake-up and the deferred `send_burst`), about 2.8 µs per traversal. The two engines add 7.5 on
  top (41.4 against 33.9), which is what their own stage clocks sum to (below). The earlier
  estimate of 6–7 µs for F-Stack's TCP was too low: the engines are cheaper on F-Stack than the
  11 µs measured on the kernel path, so more of the 17.5 µs over the floor is the stack.
- **The tails are the stack, not the engines.** The echo's p99 is 4.5 µs above its p50 and its
  p99.9 6.5 above; the ping-pong's 4.8 and 7.0. The FIX round trip on F-Stack adds 7 µs at p99
  (48.5) and 26 at p99.9 (67): the JVM's share of the tail (safepoints, the persistence write's
  page cache, the host's 1 Hz tick on the core) is the part above the echo line.
- **The engines by stage, p50, F-Stack against kernel.** Initiator: send claim → written 1.92
  against 3.81, of which the socket write 0.76 against 2.49; receive read → done 1.35 against
  1.49. Simulator: receive read → ack written 4.12 against 7.04, of which the ack's socket write
  0.76 against 2.45 and the rest of the callback 2.1 against 2.9. In-process total 7.4 against
  12.3 µs per round trip: 3.4 of the 4.9 µs saved are the two socket writes (`ff_write` queues
  an mbuf; the kernel write runs the whole TCP output path under the syscall), the remainder is
  spread over every stage by a few hundred ns (no syscalls evicting the engine's cache lines).
  The F-Stack `send.write` at 0.76 is the cost of `m_uiotombuf` plus `tcp_output` down to the
  per-lcore TX table; the NIC write happens in F-Stack's loop afterwards.
- **Stage timing itself costs ~1.5 µs** at p50 (41.4 here against 40.0 in runs 3 and 4 above
  without it; 48.3 against 45.8 on the kernel), so the headline numbers stay the earlier ones;
  this section is for the split.
- **Where the next microseconds are.** Below the engines' 7.5, nothing is left of the kernel's
  socket cost; the 11 µs of F-Stack TCP is the next target: `ff_zc_*` would remove the two
  copies (sub-µs), the rest is FreeBSD's TCP code path and its mbuf handling. Write-combining
  for the ENA LLQ (not enabled; stock vfio-pci) would shift all four rows together.
- **Method.** `run-dpdk-floor.sh dpdk` (reflector on the FIX host, paced sender on the OUCH
  host), `run-dpdk-floor.sh fstack-echo` (server on the FIX host's second ENI, client on the
  OUCH host's; both the F-Stack primary on core 3), `run-aws-latency-test.sh --transport
  fstack --stages` and `--stages`. On this stack the Windows ssh client repeatedly failed to
  return from sessions that had left a detached DPDK process behind, so the echo client was in
  the end started with `setsid` and read back from its log; the script's launches are now
  bounded by a local timeout. `logs/dpdk-floor/dpdk-20261026-211058.log`,
  `fstack-echo-20261026-211754.log`, `logs/aws-latency/20261026-191424` (fstack) and
  `20261026-191737` (kernel).

## AWS: bare metal (`a71643b`, one c7i.metal-24xl stack, persistence on, isolated, 20k + 100k at 1,000/s), µs

Both trading hosts on c7i.metal-24xl (one socket of Xeon Platinum 8488C, 48 cores at up to 3.8
GHz, 96 threads, one NUMA node, both ENA devices on it, 37.5 Gb network; `-t c7i.metal-24xl` on
`terraform-deploy.sh`, nothing else changed: the same AMI, core 3 and its sibling 51 isolated
at first boot, the hosts ready seven minutes after the apply). The same six measurements as on
c6in.2xlarge, in the same order, on one stack. FIX round trip from the intended send:

| Run | Path | min | p50 | p90 | p99 | p99.9 | p99.99 | max | TCP p50 / p99 |
|-----|------|----:|----:|----:|----:|------:|-------:|----:|--------------:|
| 1 | FIX, kernel | 41.2 | 44.9 | 47.5 | **51.5** | 78.9 | 92.8 | 347 | 53.2 / 64.4 |
| 2 | FIX, fstack | 44.6 | 48.9 | 51.4 | 54.1 | 59.2 | 99.3 | 956 | 54.6 / 60.0 |
| 3 | FIX, fstack | 44.4 | 48.5 | 51.0 | 53.6 | 58.1 | 103.6 | 382 | 52.1 / 60.6 |
| 4 | FIX, kernel | 41.5 | 45.6 | 48.2 | 52.1 | 89.4 | 107.3 | 471 | 52.4 / 63.7 |
| — | DPDK UDP ping-pong (C) | 28.5 | **31.9** | 34.3 | 36.7 | 38.8 | 41.0 | 46 | |
| — | F-Stack TCP echo (C) | 39.7 | **43.6** | 46.1 | 49.0 | 52.1 | 54.1 | 58 | |

Against c6in.2xlarge (previous sections: kernel 45.8 / 62 at p50 / p99, F-Stack 40.0 / 46.7,
ping-pong 22.7, echo 33.9):

- **Metal takes the tail out of the kernel path and nothing out of its median.** p99 goes from
  62 to 51.5, 4 µs above p90 instead of 12, p99.99 from ~115 to ~100; p50 stays at 45 (44.9 and
  45.6 against 45.76 and 45.79). The 12 µs the hypervisor used to put between p90 and p99 on the
  kernel path is gone, which is what metal was expected to buy.
- **The c7i network is 9 µs slower than c6in's at the floor.** The raw DPDK ping-pong is 31.9
  against 22.7 and the F-Stack echo 43.6 against 33.9 (the same 9.7 µs on top of a stack that
  costs the same 11.7); sockperf's kernel TCP round trip is 52–55 against 47–49. c7i is the
  non-"n" family on a 37.5 Gb Nitro card; c6in's 200 Gb card and fabric are faster at the
  packet level, and that difference is larger than anything the hypervisor adds at the median.
  So this run compares two things at once: no hypervisor, and a slower NIC generation.
- **F-Stack loses to the kernel on this host, 48.5 against 45.** F-Stack's own cost is unchanged
  (11.7 over the floor) and the engines are cheaper here (4.9 against 7.5: Sapphire Rapids at
  3.8 GHz against Ice Lake at 3.5), but both sit on the slower floor, while the kernel path has
  something the DPDK path does not: on metal the kernel driver's LLQ with write-combining and
  no virtualised interrupt path makes a kernel socket round trip (45) faster than the raw DPDK
  UDP ping-pong plus FreeBSD TCP (43.6) plus engines. The kernel's p50 is also below sockperf's
  (52–53), as on c6in: sockperf blocks in `recv`, the engines spin.
- **Engines on metal: ~5 µs per round trip** (48.5 − 43.6), against 7.5 on c6in.2xlarge and 12.3
  on the kernel path there. Stage timing was not on in these runs.
- **What would separate the two effects**: c6in.metal (the same 200 Gb Nitro card as the
  2xlarge, no hypervisor; $7.26 per host-hour) would show whether the kernel p50 drops below 45
  when the fast NIC and bare metal are combined, and whether F-Stack's 40.0 moves at all. Not
  run.
- **Host checks**: affinity, busy-spin (99–100 %), isolation and NIC IRQs PASS on both hosts in
  all four runs; the initiator's one involuntary context switch per second (its WARN, exit 1
  under `--require-isolation`) is the same as on every c6in stack. Metal booted in seven
  minutes including the isolation reboot; F-Stack and DPDK built in 75 s on 96 threads.
- Logs: `logs/aws-latency/20261026-222510` (kernel), `-222810` and `-223120` (fstack), `-223431`
  (kernel); `logs/dpdk-floor/dpdk-20261026-223801.log`, `fstack-echo-20261026-224040.log`.

## AWS: AMD metal floor (`f32d227`, one m8azn.metal-12xl stack, no JVM, 20k + 100k at 1,000/s), µs

Both trading hosts on m8azn.metal-12xl (AMD EPYC 9R05, 48 cores at up to 5.0 GHz, no SMT, one
NUMA node, 100 Gb network, $4.96 per host-hour; `trading_instance_type` in `terraform.tfvars`,
nothing else changed: the same AMI, core 3 isolated at first boot, no sibling to isolate). Only
the two C-only floor measurements, to put the fast-NIC metal next to c6in.2xlarge (hypervisor,
200 Gb card) and c7i.metal-24xl (no hypervisor, 37.5 Gb card). The engines were not deployed.

| Path | min | p50 | p90 | p99 | p99.9 | p99.99 | max |
|------|----:|----:|----:|----:|------:|-------:|----:|
| DPDK UDP ping-pong (C) | 16.0 | **17.2** | 17.9 | 18.9 | 20.6 | 21.1 | 22.5 |
| F-Stack TCP echo (C) | 22.1 | **23.5** | 24.3 | 25.3 | 26.1 | 26.8 | 33.7 |

Against the other two hosts (ping-pong / echo at p50, and the difference, which is F-Stack's
TCP per round trip):

| Host | ping-pong | echo | F-Stack TCP | ping-pong p99.99 |
|------|----------:|-----:|------------:|-----------------:|
| c6in.2xlarge (hypervisor, 200 Gb card) | 22.7 | 33.9 | 11.2 | ~33 |
| c7i.metal-24xl (metal, 37.5 Gb card) | 31.9 | 43.6 | 11.7 | 41.0 |
| m8azn.metal-12xl (metal, 100 Gb card, 5 GHz) | 17.2 | 23.5 | 6.3 | 21.1 |

- **The network floor is 17.2, 5.5 µs under c6in's and 14.7 under c7i metal's.** With the
  hypervisor gone and a current-generation card, the raw DPDK round trip between two hosts is
  17 µs, and the tail is flat: p99.99 is 4 µs above p50 (c6in: ~10, c7i metal: 9), max 22.5.
  This is the lowest network number measured on AWS so far.
- **F-Stack's TCP costs 6.3 µs here against 11–12 on the Intel hosts.** The stack is the same
  build and configuration; the 5 GHz Zen 5 core runs the four FreeBSD crossings in a little over
  half the time of Ice Lake at 3.5 or Sapphire Rapids at 3.8. So the per-crossing cost is
  ~1.6 µs here, still several times a from-scratch stack's, but the gap to the floor is smaller.
- **Projected FIX round trip**: the engines took ~5 µs on Sapphire Rapids metal and ~7.5 on
  c6in; at 5 GHz expect ~4. On F-Stack that puts FIX near 23.5 + 4 ≈ 27–28 µs, against 40.0 on
  c6in.2xlarge and 48.5 on c7i metal. The kernel path was not measured here (no sockperf, no
  engines); on c7i metal it beat F-Stack because the floor was slow, which is not the case on
  this host, so the ordering is likely F-Stack first. A full `run-aws-latency-test.sh` pair on
  this type (kernel and `--transport fstack`) would settle it at about $15 of instance time.
- Method: `terraform-deploy.sh` with the type in `terraform.tfvars`, `wait-for-hosts.sh`, then
  `run-dpdk-floor.sh enis`, `setup`, `dpdk 100000 20000 1000`, `fstack`,
  `fstack-echo 100000 20000 1000 200`, `cleanup`, destroy. DPDK
  24.11 and F-Stack built in under two minutes on 48 cores. No ssh hangs this time (every
  phase launch bounded by the script's `timeout`/`setsid` and the local `timeout`).
- Logs: `logs/dpdk-floor/dpdk-20261027-115041.log`, `fstack-echo-20261027-115452.log`.

## AWS: AMD metal, FIX on both transports (`1cb61c8`, one m8azn.metal-12xl stack, persistence on, isolated, 20k + 100k at 1,000/s), µs

The same host type as the floor section above (EPYC 9R05 at up to 5.0 GHz, no SMT, 100 Gb
card), this time with the engines deployed and the four FIX runs in the usual order. FIX round
trip from the intended send; TCP is sockperf on the kernel ENI:

| Run | Path | min | p50 | p90 | p99 | p99.9 | p99.99 | max | TCP p50 / p99 |
|-----|------|----:|----:|----:|----:|------:|-------:|----:|--------------:|
| 1 | FIX, kernel | 30.9 | 34.1 | 35.3 | 38.6 | 70.3 | 208 | 1149 | 261 / 264 (see below) |
| 2 | FIX, fstack | 25.9 | **27.6** | 28.6 | **30.7** | 38.2 | 81.3 | 247 | 21.3 / 32.0 |
| 3 | FIX, fstack | 25.8 | **27.6** | 28.7 | 31.1 | 39.9 | 132 | 3492 | 21.7 / 28.1 |
| 4 | FIX, kernel | 33.7 | 37.6 | 39.3 | 42.9 | 86.3 | 189 | 1059 | 21.3 / 27.3 |

With the floor rows from the previous section (ping-pong 17.2, F-Stack echo 23.5), and against
the other hosts:

| Host | FIX kernel p50 / p99 | FIX fstack p50 / p99 | ping-pong | echo | sockperf TCP |
|------|---------------------:|---------------------:|----------:|-----:|-------------:|
| c6in.2xlarge | 45.8 / 62 | 40.0 / 46.7 | 22.7 | 33.9 | 47–49 |
| c7i.metal-24xl | 45 / 51.5 | 48.5 / 54 | 31.9 | 43.6 | 52–55 |
| m8azn.metal-12xl | 34–38 / 39–43 | 27.6 / 31 | 17.2 | 23.5 | 21.3 |

- **FIX on F-Stack: 27.6 µs at p50, 31 at p99**, both runs within 0.1 and 0.4 of each other.
  That is 12.4 µs under c6in.2xlarge's 40.0 and the lowest FIX round trip measured on AWS. The
  engines take 4.1 (27.6 − 23.5), as projected from the clock: 7.5 on Ice Lake at 3.5 GHz, ~5
  on Sapphire Rapids at 3.8, 4.1 on Zen 5 at 5.0.
- **The kernel path is 34–38 at p50, 39–43 at p99.** The two kernel runs differ by 3.4 µs at
  every percentile (run 1 34.1, run 4 37.6; min 30.9 against 33.7), more than the usual
  run-to-run spread; the F-Stack pair in between agrees to 0.1. Either way F-Stack wins by 7–10
  at p50 and 8–12 at p99 on this host, the reverse of c7i metal, because here the DPDK floor is
  fast.
- **Kernel TCP is fast on this metal: sockperf 21.3 at p50**, against 47–49 on c6in.2xlarge and
  52–55 on c7i metal, and only 4 µs above the raw DPDK ping-pong (17.2). With no hypervisor,
  direct interrupts and the ENA driver's LLQ with write-combining, a blocking kernel socket
  round trip costs about what the poll-mode driver does. The FIX kernel path is then 13–16 over
  sockperf, where the engines are 4: the rest is the kernel socket calls per message (the
  `send.write` 2.5 µs and the read side measured with `--stages` on c6in) and the epoll pass.
- **Run 1's sockperf read 261 µs at p50 with a 2.8 µs standard deviation**, then 21 in runs
  2–4 with nothing changed; the FIX run in the same window was normal (34.1). A constant
  ~240 µs with no spread looks like a moderation timer on the first use of the kernel ENI after
  boot rather than the network; not investigated, the line is kept as it was recorded
  (`tcp_rtt_us` of the first `m8azn.metal-12xl` history line), compare the other three.
- **Tails**: F-Stack p99.9 is 38–40 and p99.99 81–132; the kernel path's p99.9 is 70–86 and
  p99.99 ~200. Both have single outliers over 1 ms (max 1149 and 1059 on the kernel runs, 3492
  on fstack run 3), as on every stack.
- **Host checks**: affinity, busy-spin (100 %), isolation and NIC IRQs PASS on both hosts; the
  initiator's one involuntary context switch per second (WARN, exit 1 under
  `--require-isolation`) in runs 1–3 and none in run 4. No SMT on this host, so nothing to
  isolate beside the core.
- **Cost**: $4.96 per host-hour; the whole stack from apply to destroy ran about 45 minutes.
- Logs: `logs/aws-latency/20261027-123439` (kernel), `-123741` and `-124052` (fstack), `-124404`
  (kernel).
