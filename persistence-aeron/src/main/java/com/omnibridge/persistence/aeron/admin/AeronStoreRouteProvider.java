package com.omnibridge.persistence.aeron.admin;

import com.omnibridge.admin.routes.RouteProvider;
import com.omnibridge.persistence.aeron.AeronRemoteStore;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Route provider exposing Aeron remote store status, publishers, and streams via REST.
 */
public class AeronStoreRouteProvider implements RouteProvider {

    private static final Logger log = LoggerFactory.getLogger(AeronStoreRouteProvider.class);

    private final AeronRemoteStore store;

    public AeronStoreRouteProvider(AeronRemoteStore store) {
        this.store = store;
    }

    @Override
    public String getBasePath() {
        return "/store";
    }

    @Override
    public void registerRoutes(Javalin app, String contextPath) {
        String base = contextPath + getBasePath();

        app.get(base + "/status", ctx -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("status", store.getState().name());
            status.put("entriesReceived", store.getEntriesReceived());
            status.put("publisherCount", store.getPublisherStates().size());
            status.put("streamCount", store.getStore().getStreamNames().size());
            ctx.json(status);
        });

        app.get(base + "/publishers", ctx -> {
            List<Map<String, Object>> publishers = new ArrayList<>();
            store.getPublisherStates().forEach((id, state) -> {
                Map<String, Object> pub = new LinkedHashMap<>();
                pub.put("publisherId", id);
                pub.put("entriesReceived", state.getEntriesReceived());
                pub.put("lastTimestamp", state.getLastTimestamp());
                pub.put("lastSeqNum", state.getLastSeqNum());
                publishers.add(pub);
            });
            ctx.json(publishers);
        });

        app.get(base + "/streams", ctx -> {
            Collection<String> streamNames = store.getStore().getStreamNames();
            List<Map<String, Object>> streams = new ArrayList<>();
            for (String streamName : streamNames) {
                Map<String, Object> stream = new LinkedHashMap<>();
                stream.put("streamName", streamName);
                stream.put("entryCount", store.getStore().getEntryCount(streamName));
                streams.add(stream);
            }
            ctx.json(streams);
        });

        log.info("Registered Aeron store endpoints at {}", base);
    }

    @Override
    public String getDescription() {
        return "Aeron remote store status and data endpoints";
    }
}
