package com.omnibridge.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A message the socket only partly accepts must still reach the peer whole,
 * and a write failure must surface instead of silently consuming the message.
 */
class TcpChannelPartialWriteTest {

    private static final int SMALL_SOCKET_BUFFER = 8 * 1024;
    private static final int WRITE_BUFFER = 1024;
    private static final int RING_CAPACITY = 16 * 1024 * 1024;

    private ServerSocketChannel server;
    private SocketChannel client;
    private SocketChannel peer;
    private TcpChannel channel;

    @BeforeEach
    void connect() throws IOException {
        server = ServerSocketChannel.open();
        server.setOption(StandardSocketOptions.SO_RCVBUF, SMALL_SOCKET_BUFFER);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

        client = SocketChannel.open();
        client.setOption(StandardSocketOptions.SO_SNDBUF, SMALL_SOCKET_BUFFER);
        client.connect(server.getLocalAddress());
        peer = server.accept();
        client.configureBlocking(false);

        channel = new TcpChannel(client, 4096, WRITE_BUFFER, RING_CAPACITY);
    }

    @AfterEach
    void close() throws IOException {
        client.close();
        peer.close();
        server.close();
    }

    @Test
    void messageLargerThanWriteBufferArrivesWhole() throws Exception {
        byte[] payload = new byte[1024 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('A' + i % 26);
        }
        assertEquals(payload.length, channel.writeRaw(payload, 0, payload.length));

        // Peer not reading yet: the socket fills and the write stops part-way.
        channel.drainRingBufferToSocket();

        CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> readAll(payload.length));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((channel.hasRingBufferMessages() || !channel.flush()) && System.nanoTime() < deadline) {
            channel.drainRingBufferToSocket();
            Thread.sleep(1);
        }

        byte[] got = received.get(10, TimeUnit.SECONDS);
        assertEquals(payload.length, got.length, "bytes delivered to the peer");
        assertArrayEquals(payload, got);
    }

    @Test
    void writeFailureIsReportedNotSwallowed() throws Exception {
        peer.setOption(StandardSocketOptions.SO_LINGER, 0);
        peer.close();
        Thread.sleep(200);

        byte[] msg = "8=FIX.4.4|9=5|35=0|10=000|".getBytes();
        channel.writeRaw(msg, 0, msg.length);
        channel.writeRaw(msg, 0, msg.length);

        assertThrows(IOException.class, () -> {
            for (int i = 0; i < 10; i++) {
                channel.drainRingBufferToSocket();
                channel.writeRaw(msg, 0, msg.length);
                Thread.sleep(20);
            }
        }, "writing to a reset connection must raise so the event loop closes the channel");
    }

    private byte[] readAll(int expected) {
        byte[] buf = new byte[expected];
        int total = 0;
        try {
            peer.socket().setSoTimeout(3000);
            InputStream in = peer.socket().getInputStream();
            while (total < expected) {
                int n = in.read(buf, total, expected - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } catch (IOException e) {
            // timed out: return what arrived
        }
        return Arrays.copyOf(buf, total);
    }
}
