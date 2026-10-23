package com.omnibridge.benchmarks;

import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * {@link IncomingFixMessage} field access on a parsed ExecutionReport.
 *
 * <p>{@code numericFields} reads int/double/char fields of an already parsed message.
 * CharSequence getters hand out wrappers from a per-message pool that is refilled only when the
 * message is re-wrapped, so they are measured together with the wrap, the way a listener sees
 * each message: {@code wrapOnly} is the parse alone, {@code wrapAndReadListenerFields} adds
 * what the latency initiator reads (MsgType, ExecType, ClOrdID), {@code wrapAndReadAll} reads
 * every body field.</p>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = "-Dagrona.disable.bounds.checks=true")
public class IncomingFieldAccessBenchmark {

    private final IncomingFixMessage message = new IncomingFixMessage();
    private UnsafeBuffer bytes;
    private int length;

    @Setup
    public void setup() {
        bytes = FixMessages.executionReports(1);
        length = bytes.capacity();
        message.wrap(bytes, 0, length);
    }

    @Benchmark
    public void numericFields(Blackhole bh) {
        bh.consume(message.getChar(FixTags.ExecType));
        bh.consume(message.getChar(FixTags.OrdStatus));
        bh.consume(message.getInt(FixTags.OrderQty));
        bh.consume(message.getDouble(FixTags.Price));
        bh.consume(message.getDouble(FixTags.CumQty));
        bh.consume(message.getDouble(FixTags.LeavesQty));
        bh.consume(message.getSeqNum());
    }

    @Benchmark
    public int wrapOnly() {
        message.wrap(bytes, 0, length);
        return message.getSeqNum();
    }

    @Benchmark
    public void wrapAndReadListenerFields(Blackhole bh) {
        message.wrap(bytes, 0, length);
        bh.consume(message.getMsgType());
        bh.consume(message.getChar(FixTags.ExecType));
        bh.consume(message.getCharSequence(FixTags.ClOrdID));
    }

    @Benchmark
    public void wrapAndReadAll(Blackhole bh) {
        message.wrap(bytes, 0, length);
        bh.consume(message.getMsgType());
        bh.consume(message.getCharSequence(FixTags.OrderID));
        bh.consume(message.getCharSequence(FixTags.ExecID));
        bh.consume(message.getCharSequence(FixTags.ClOrdID));
        bh.consume(message.getCharSequence(FixTags.Symbol));
        bh.consume(message.getChar(FixTags.ExecType));
        bh.consume(message.getChar(FixTags.OrdStatus));
        bh.consume(message.getChar(FixTags.Side));
        bh.consume(message.getInt(FixTags.OrderQty));
        bh.consume(message.getDouble(FixTags.Price));
        bh.consume(message.getDouble(FixTags.CumQty));
        bh.consume(message.getDouble(FixTags.LeavesQty));
        bh.consume(message.getDouble(FixTags.AvgPx));
    }
}
