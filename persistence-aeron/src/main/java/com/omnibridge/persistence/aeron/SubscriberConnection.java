package com.omnibridge.persistence.aeron;

import com.omnibridge.persistence.aeron.codec.MessageTypes;
import com.omnibridge.persistence.aeron.config.SubscriberConfig;
import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages Aeron publications for a single remote subscriber.
 *
 * <p>Each subscriber has two publications:
 * <ul>
 *   <li>Data publication (stream ID 1): log entry replication</li>
 *   <li>Control publication (stream ID 10): replay requests, heartbeats</li>
 * </ul>
 */
public class SubscriberConnection {

    private static final Logger log = LoggerFactory.getLogger(SubscriberConnection.class);

    private final SubscriberConfig config;
    private final Publication dataPublication;
    private final Publication controlPublication;
    private volatile boolean connected;
    private volatile boolean previouslyConnected;
    private volatile boolean catchUpNeeded;
    private long lastHeartbeatSentMs;
    private long backpressureCount;

    public SubscriberConnection(Aeron aeron, SubscriberConfig config) {
        this.config = config;
        this.dataPublication = aeron.addPublication(config.getDataChannel(), MessageTypes.DATA_STREAM_ID);
        this.controlPublication = aeron.addPublication(config.getControlChannel(), MessageTypes.CONTROL_STREAM_ID);
        log.info("Created subscriber connection: {} (data={}, control={})",
                config.getName(), config.getDataChannel(), config.getControlChannel());
    }

    public long offerData(DirectBuffer buffer, int offset, int length) {
        long result = dataPublication.offer(buffer, offset, length);
        if (result < 0) {
            handleOfferFailure(result, "data");
        } else {
            connected = true;
        }
        return result;
    }

    public long offerControl(DirectBuffer buffer, int offset, int length) {
        long result = controlPublication.offer(buffer, offset, length);
        if (result < 0) {
            handleOfferFailure(result, "control");
        }
        return result;
    }

    private void handleOfferFailure(long result, String channelType) {
        if (result == Publication.BACK_PRESSURED) {
            backpressureCount++;
            if (backpressureCount % 1000 == 1) {
                log.warn("Backpressure on {} channel to {} (count={})",
                        channelType, config.getName(), backpressureCount);
            }
        } else if (result == Publication.NOT_CONNECTED) {
            if (connected) {
                log.warn("Subscriber {} disconnected on {} channel", config.getName(), channelType);
                connected = false;
            }
        } else if (result == Publication.CLOSED) {
            log.error("Publication closed for {} on {} channel", config.getName(), channelType);
        }
    }

    public boolean isConnected() {
        return dataPublication.isConnected();
    }

    /**
     * Check if the subscriber just transitioned from disconnected to connected.
     * Returns true exactly once per connection event, then resets.
     *
     * <p>Call this periodically to detect when a subscriber comes online
     * and needs a catch-up sync of missed entries.</p>
     *
     * @return true if subscriber just connected (consumes the event)
     */
    public boolean checkAndClearCatchUpNeeded() {
        boolean nowConnected = dataPublication.isConnected();
        if (nowConnected && !previouslyConnected) {
            previouslyConnected = true;
            connected = true;
            catchUpNeeded = true;
            log.info("Subscriber {} connected — catch-up sync needed", config.getName());
        } else if (!nowConnected && previouslyConnected) {
            previouslyConnected = false;
            connected = false;
            log.warn("Subscriber {} disconnected", config.getName());
        }

        if (catchUpNeeded) {
            catchUpNeeded = false;
            return true;
        }
        return false;
    }

    public SubscriberConfig getConfig() {
        return config;
    }

    public long getLastHeartbeatSentMs() {
        return lastHeartbeatSentMs;
    }

    public void setLastHeartbeatSentMs(long lastHeartbeatSentMs) {
        this.lastHeartbeatSentMs = lastHeartbeatSentMs;
    }

    public long getBackpressureCount() {
        return backpressureCount;
    }

    public void close() {
        dataPublication.close();
        controlPublication.close();
        log.info("Closed subscriber connection: {}", config.getName());
    }
}
