package com.fixengine.ouch.message;

import com.fixengine.ouch.message.v50.*;
import org.agrona.DirectBuffer;

/**
 * Listener interface for typed OUCH message callbacks.
 *
 * <p>All methods have default empty implementations, allowing listeners
 * to override only the message types they care about.</p>
 *
 * <p>Supports both OUCH 4.2 and 5.0 protocols. V4.2 callbacks receive the base
 * message types (e.g., OrderAcceptedMessage). V5.0 callbacks receive V50-specific
 * types (e.g., V50OrderAcceptedMessage). By default, V5.0 callbacks delegate to
 * V4.2 callbacks for backward compatibility.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * session.addMessageListener(new OuchMessageListener() {
 *     @Override
 *     public void onOrderAccepted(OuchSession session, OrderAcceptedMessage msg) {
 *         System.out.println("Order accepted: " + msg.getOrderToken());
 *     }
 *
 *     @Override
 *     public void onOrderExecuted(OuchSession session, OrderExecutedMessage msg) {
 *         System.out.println("Executed: " + msg.getExecutedShares() + " shares");
 *     }
 * });
 * }</pre>
 *
 * @param <S> the session type (allows for custom session implementations)
 */
public interface OuchMessageListener<S> {

    // =====================================================
    // Exchange -> Client (when acting as initiator/client)
    // =====================================================

    /**
     * Called when an Order Accepted message is received.
     * Confirms that the exchange accepted a new order.
     */
    default void onOrderAccepted(S session, OrderAcceptedMessage msg) {}

    /**
     * Called when an Order Executed message is received.
     * Indicates a fill occurred on an order.
     */
    default void onOrderExecuted(S session, OrderExecutedMessage msg) {}

    /**
     * Called when an Order Canceled message is received.
     * Confirms that an order was canceled (fully or partially).
     */
    default void onOrderCanceled(S session, OrderCanceledMessage msg) {}

    /**
     * Called when an Order Rejected message is received.
     * Indicates that an order submission was rejected.
     */
    default void onOrderRejected(S session, OrderRejectedMessage msg) {}

    /**
     * Called when an Order Replaced message is received.
     * Confirms that an order modification was accepted.
     */
    default void onOrderReplaced(S session, OrderReplacedMessage msg) {}

    /**
     * Called when a System Event message is received.
     * Indicates market-wide events (start/end of day, etc.).
     */
    default void onSystemEvent(S session, SystemEventMessage msg) {}

    // =====================================================
    // Client -> Exchange (when acting as acceptor/server)
    // =====================================================

    /**
     * Called when an Enter Order message is received.
     * Indicates a client submitted a new order.
     */
    default void onEnterOrder(S session, EnterOrderMessage msg) {}

    /**
     * Called when a Cancel Order message is received.
     * Indicates a client requested to cancel an order.
     */
    default void onCancelOrder(S session, CancelOrderMessage msg) {}

    /**
     * Called when a Replace Order message is received.
     * Indicates a client requested to modify an order.
     */
    default void onReplaceOrder(S session, ReplaceOrderMessage msg) {}

    // =====================================================
    // Generic/Unknown messages
    // =====================================================

    /**
     * Called when an unknown or unhandled message type is received.
     * Provides access to the raw message buffer for custom processing.
     *
     * @param session the session that received the message
     * @param type the message type (may be UNKNOWN)
     * @param buffer the raw message buffer
     * @param offset the offset within the buffer
     * @param length the message length
     */
    default void onUnknownMessage(S session, OuchMessageType type,
                                   DirectBuffer buffer, int offset, int length) {}

    /**
     * Called for any message, after the type-specific callback.
     * Useful for logging, metrics, or general message processing.
     *
     * @param session the session that received the message
     * @param msg the received message
     */
    default void onMessage(S session, OuchMessage msg) {}

    // =====================================================
    // OUCH 5.0 specific callbacks (V50)
    // By default, these do nothing. Override for V50-specific handling.
    // =====================================================

    /**
     * Called when an OUCH 5.0 Order Accepted message is received.
     */
    default void onOrderAcceptedV50(S session, V50OrderAcceptedMessage msg) {}

    /**
     * Called when an OUCH 5.0 Order Executed message is received.
     */
    default void onOrderExecutedV50(S session, V50OrderExecutedMessage msg) {}

    /**
     * Called when an OUCH 5.0 Order Canceled message is received.
     */
    default void onOrderCanceledV50(S session, V50OrderCanceledMessage msg) {}

    /**
     * Called when an OUCH 5.0 Order Rejected message is received.
     */
    default void onOrderRejectedV50(S session, V50OrderRejectedMessage msg) {}

    /**
     * Called when an OUCH 5.0 Order Replaced message is received.
     */
    default void onOrderReplacedV50(S session, V50OrderReplacedMessage msg) {}

    /**
     * Called when an OUCH 5.0 System Event message is received.
     */
    default void onSystemEventV50(S session, V50SystemEventMessage msg) {}

    /**
     * Called when an OUCH 5.0 Enter Order message is received.
     */
    default void onEnterOrderV50(S session, V50EnterOrderMessage msg) {}

    /**
     * Called when an OUCH 5.0 Cancel Order message is received.
     */
    default void onCancelOrderV50(S session, V50CancelOrderMessage msg) {}

    /**
     * Called when an OUCH 5.0 Replace Order message is received.
     */
    default void onReplaceOrderV50(S session, V50ReplaceOrderMessage msg) {}
}
