package com.omnibridge.benchmarks;

import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

/**
 * SendingTime (tag 52) encoding on its own: {@code RingBufferOutgoingMessage.encodeSendingTime}
 * is private, so it is called through a constant MethodHandle (inlined by the JIT).
 * {@code sameMillisecond} keeps the input fixed; otherwise it moves by a millisecond per call.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = "-Dagrona.disable.bounds.checks=true")
public class SendingTimeBenchmark {

    private static final MethodHandle ENCODE_SENDING_TIME;

    static {
        try {
            ENCODE_SENDING_TIME = MethodHandles
                    .privateLookupIn(RingBufferOutgoingMessage.class, MethodHandles.lookup())
                    .findVirtual(RingBufferOutgoingMessage.class, "encodeSendingTime",
                            MethodType.methodType(void.class, long.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private RingBufferOutgoingMessage msg;
    private long millis = FixMessages.SENDING_TIME_MILLIS;

    @Setup
    public void setup() {
        UnsafeBuffer buffer = FixMessages.newBuffer();
        msg = new RingBufferOutgoingMessage(FixMessages.poolConfig());
        msg.wrap(buffer, 0, FixMessages.MAX_MESSAGE_LENGTH, 0, 0,
                FixMessages.BEGIN_STRING, FixMessages.SENDER, FixMessages.TARGET,
                FixTags.MsgTypes.NewOrderSingle);
    }

    @Benchmark
    public void sameMillisecond() throws Throwable {
        ENCODE_SENDING_TIME.invokeExact(msg, FixMessages.SENDING_TIME_MILLIS);
    }

    @Benchmark
    public void advancingMillisecond() throws Throwable {
        ENCODE_SENDING_TIME.invokeExact(msg, millis++);
    }
}
