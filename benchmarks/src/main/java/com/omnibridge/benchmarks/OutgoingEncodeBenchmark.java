package com.omnibridge.benchmarks;

import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Outgoing encode through {@link RingBufferOutgoingMessage}: wrap a claimed region (header
 * template copy), set the NewOrderSingle body, then prepareForSend (SendingTime, BodyLength,
 * CheckSum). {@code msgType} covers the 1- and 2-character header template paths.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = "-Dagrona.disable.bounds.checks=true")
public class OutgoingEncodeBenchmark {

    @Param({"D", "AE"})
    public String msgType;

    private UnsafeBuffer buffer;
    private RingBufferOutgoingMessage msg;
    private final StringBuilder clOrdId = new StringBuilder(32);
    private int counter;

    @Setup
    public void setup() {
        buffer = FixMessages.newBuffer();
        msg = new RingBufferOutgoingMessage(FixMessages.poolConfig());
    }

    /** wrap + header template only. */
    @Benchmark
    public int wrapHeader() {
        msg.wrap(buffer, 0, FixMessages.MAX_MESSAGE_LENGTH, 0, 0,
                FixMessages.BEGIN_STRING, FixMessages.SENDER, FixMessages.TARGET, msgType);
        return msg.getClaimIndex();
    }

    /** wrap + body fields + prepareForSend: what tryClaimMessage..commitMessage encodes. */
    @Benchmark
    public int encodeNewOrder() {
        int i = counter++;
        msg.wrap(buffer, 0, FixMessages.MAX_MESSAGE_LENGTH, 0, 0,
                FixMessages.BEGIN_STRING, FixMessages.SENDER, FixMessages.TARGET, msgType);
        clOrdId.setLength(0);
        clOrdId.append("TEST").append(i & 0xFFFFF);
        FixMessages.newOrderFields(msg, clOrdId, 100 + (i & 0xFF));
        return msg.prepareForSend(FixMessages.SENDING_TIME_MILLIS + (i & 0x3FF));
    }
}
