package com.omnibridge.persistence.aeron;

import com.omnibridge.persistence.aeron.codec.MessageTypes;
import com.omnibridge.persistence.aeron.config.AeronLogStoreConfig;
import com.omnibridge.persistence.aeron.config.SubscriberConfig;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages Aeron MediaDriver and Publication/Subscription lifecycle for the client side.
 */
public class AeronTransport {

    private static final Logger log = LoggerFactory.getLogger(AeronTransport.class);

    private final AeronLogStoreConfig config;
    private MediaDriver mediaDriver;
    private Aeron aeron;
    private final List<SubscriberConnection> subscribers = new ArrayList<>();
    private Subscription replaySubscription;
    private volatile boolean running;

    public AeronTransport(AeronLogStoreConfig config) {
        this.config = config;
    }

    public void start() {
        if (config.isEmbeddedMediaDriver()) {
            MediaDriver.Context driverCtx = new MediaDriver.Context()
                    .threadingMode(ThreadingMode.SHARED)
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true);

            String aeronDir = config.getAeronDir();
            if (aeronDir != null && !aeronDir.isEmpty()) {
                driverCtx.aeronDirectoryName(aeronDir);
            }

            mediaDriver = MediaDriver.launchEmbedded(driverCtx);
            log.info("Embedded MediaDriver started: {}", mediaDriver.aeronDirectoryName());
        }

        Aeron.Context aeronCtx = new Aeron.Context();
        if (mediaDriver != null) {
            aeronCtx.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        }
        aeron = Aeron.connect(aeronCtx);

        // Create subscriber connections
        for (SubscriberConfig subConfig : config.getSubscribers()) {
            subscribers.add(new SubscriberConnection(aeron, subConfig));
        }

        // Create replay subscription to receive replay data from subscribers
        replaySubscription = aeron.addSubscription(
                config.getReplayChannel(), MessageTypes.REPLAY_STREAM_ID);
        log.info("Replay subscription created: channel={}, streamId={}",
                config.getReplayChannel(), MessageTypes.REPLAY_STREAM_ID);

        running = true;
        log.info("AeronTransport started with {} subscribers", subscribers.size());
    }

    public void publishEntry(DirectBuffer buffer, int offset, int length) {
        for (SubscriberConnection sub : subscribers) {
            sub.offerData(buffer, offset, length);
        }
    }

    public void publishControl(DirectBuffer buffer, int offset, int length) {
        for (SubscriberConnection sub : subscribers) {
            sub.offerControl(buffer, offset, length);
        }
    }

    public void publishControlTo(int subscriberIndex, DirectBuffer buffer, int offset, int length) {
        if (subscriberIndex >= 0 && subscriberIndex < subscribers.size()) {
            subscribers.get(subscriberIndex).offerControl(buffer, offset, length);
        }
    }

    public Subscription getReplaySubscription() {
        return replaySubscription;
    }

    public List<SubscriberConnection> getSubscribers() {
        return Collections.unmodifiableList(subscribers);
    }

    public Aeron getAeron() {
        return aeron;
    }

    public boolean isRunning() {
        return running;
    }

    public IdleStrategy createIdleStrategy() {
        return switch (config.getIdleStrategy()) {
            case "busy-spin" -> new BusySpinIdleStrategy();
            case "yielding" -> new YieldingIdleStrategy();
            default -> new SleepingIdleStrategy(1_000_000); // 1ms
        };
    }

    public void stop() {
        running = false;

        for (SubscriberConnection sub : subscribers) {
            sub.close();
        }
        subscribers.clear();

        if (replaySubscription != null) {
            replaySubscription.close();
        }

        if (aeron != null) {
            aeron.close();
        }

        if (mediaDriver != null) {
            mediaDriver.close();
        }

        log.info("AeronTransport stopped");
    }
}
