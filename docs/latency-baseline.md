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

## Round trip, µs

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

## Where the time goes (stage timing, mean µs, stages run 1)

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
   on the initiator, in every run, while the commit inside it is only 12.9 µs at p99. Something in
   `AcceptorMessageListener` / `sendExecutionReport` (String building, allocation, GC) causes it;
   worth finding before reading the tails further.
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
