package com.omnibridge.benchmarks;

import com.omnibridge.fix.message.FixReader;
import com.omnibridge.fix.message.IncomingFixMessage;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * {@link FixReader} framing, checksum and field indexing of ExecutionReports, delivered one
 * per read or ten per read. Scores are per message.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = "-Dagrona.disable.bounds.checks=true")
public class FixReaderBenchmark {

    private static final int BATCH = 10;

    private final FixReader reader = new FixReader();
    private final IncomingFixMessage message = new IncomingFixMessage();
    private UnsafeBuffer single;
    private UnsafeBuffer batch;

    @Setup
    public void setup() {
        single = FixMessages.executionReports(1);
        batch = FixMessages.executionReports(BATCH);
    }

    @Benchmark
    public int parseSingle() {
        reader.addData(single, 0, single.capacity());
        return readAll();
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public int parseBatch() {
        reader.addData(batch, 0, batch.capacity());
        return readAll();
    }

    private int readAll() {
        int n = 0;
        while (reader.readIncomingMessage(message)) {
            n += message.getSeqNum();
        }
        return n;
    }
}
