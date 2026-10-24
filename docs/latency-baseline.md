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

All three runs on one stack, event-loop cpus isolated as provisioned. "Chronicle only": the
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
