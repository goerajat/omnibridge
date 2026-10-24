package com.omnibridge.simulator.core.order;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * addOrder runs on the simulator's event loop, and filled orders stay in the book. A
 * ConcurrentHashMap that grows copies every entry inside that put (about 20 ms at 98,304
 * entries, 40 ms on AWS), so the book's maps are allocated at their full size up front.
 */
class DefaultOrderBookTest {

    private static Order order(long id, String clOrdId, String session) {
        return Order.builder()
                .orderId(id)
                .clientOrderId(clOrdId)
                .symbol("AAPL")
                .side(OrderSide.BUY)
                .orderType(OrderType.LIMIT)
                .qty(100)
                .price(150.0)
                .sessionId(session)
                .protocol("FIX")
                .build();
    }

    @Test
    void defaultBookIsAllocatedForAMillionOrders() throws Exception {
        DefaultOrderBook book = new DefaultOrderBook();
        assertEquals(2, tableLengths(book).size(), "maps allocated at construction, with the ClOrdID-only index");
        // A session's ClOrdID map is allocated at its first order, at the same size
        assertTrue(book.addOrder(order(1, "ORD1", "S1")));
        List<Integer> lengths = tableLengths(book);
        assertEquals(3, lengths.size(), "maps after the first order");
        for (int len : lengths) {
            // ConcurrentHashMap grows at 3/4 of its table
            assertTrue(len * 3L / 4 >= DefaultOrderBook.DEFAULT_EXPECTED_ORDERS,
                    "tables allocated at construction, large enough for a million orders: " + lengths);
        }
    }

    @Test
    void presizedBookNeverResizesUpToTheExpectedOrders() throws Exception {
        int expected = 200_000;
        for (boolean demo : new boolean[] {true, false}) {
            DefaultOrderBook book = new DefaultOrderBook(expected, demo);
            assertTrue(book.addOrder(order(1, "ORD1", "S1")));
            List<Integer> before = tableLengths(book);
            for (int i = 2; i <= expected; i++) {
                assertTrue(book.addOrder(order(i, "ORD" + i, "S1")));
            }
            assertEquals(before, tableLengths(book), "table lengths after " + expected + " orders, demo=" + demo);
            assertEquals(expected, book.getOrderCount());
        }
    }

    @Test
    void withoutTheDemoIndexLookupsStillWork() throws Exception {
        DefaultOrderBook book = new DefaultOrderBook(1024, false);
        assertEquals(1, tableLengths(book).size(), "no ClOrdID-only map outside demo mode");

        Order a = order(1, "ORD1", "S1");
        Order b = order(2, "ORD1", "S2");
        Order c = order(3, "ORD2", "S1");
        assertTrue(book.addOrder(a));
        assertTrue(book.addOrder(b));
        assertTrue(book.addOrder(c));
        assertFalse(book.addOrder(order(1, "OTHER", "S1")), "duplicate order id");

        assertSame(a, book.getOrderByClientId("ORD1", "S1").orElseThrow());
        assertSame(b, book.getOrderByClientId("ORD1", "S2").orElseThrow());
        assertSame(c, book.getOrderByClientId("ORD2").orElseThrow());
        assertTrue(book.getOrderByClientId("ORD1").isPresent());
        assertTrue(book.getOrderByClientId("NONE").isEmpty());

        assertSame(a, book.removeOrder(1).orElseThrow());
        assertTrue(book.getOrderByClientId("ORD1", "S1").isEmpty());
        assertSame(b, book.getOrderByClientId("ORD1").orElseThrow());
        book.clear();
        assertEquals(0, book.getOrderCount());
    }

    @Test
    void demoIndexKeepsTheLastOrderPerClOrdId() {
        DefaultOrderBook book = new DefaultOrderBook(1024, true);
        Order a = order(1, "ORD1", "S1");
        Order b = order(2, "ORD1", "S2");
        book.addOrder(a);
        book.addOrder(b);
        assertSame(b, book.getOrderByClientId("ORD1").orElseThrow());
        // Removing the older order leaves the newer one findable
        book.removeOrder(1);
        assertSame(b, book.getOrderByClientId("ORD1").orElseThrow());
    }

    /**
     * Lengths of the backing tables of the book's order maps: every ConcurrentHashMap field,
     * and the per-session maps inside the one that holds maps (the outer map, keyed by session,
     * stays small and is left out).
     */
    static List<Integer> tableLengths(DefaultOrderBook book) throws Exception {
        Field table = ConcurrentHashMap.class.getDeclaredField("table");
        table.setAccessible(true);
        List<Integer> lengths = new ArrayList<>();
        for (Field f : DefaultOrderBook.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Map<?, ?> map = (Map<?, ?>) f.get(book);
                if (map == null) {
                    continue;
                }
                boolean holdsMaps = !map.isEmpty() && map.values().iterator().next() instanceof Map;
                if (holdsMaps) {
                    for (Object inner : map.values()) {
                        lengths.add(tableLength(table, inner));
                    }
                } else if (!f.getGenericType().getTypeName().contains("Map<java.lang.String, java.util.Map")) {
                    lengths.add(tableLength(table, map));
                }
            }
        }
        return lengths;
    }

    private static int tableLength(Field table, Object map) throws Exception {
        Object[] t = (Object[]) table.get(map);
        return t == null ? 0 : t.length;
    }
}
