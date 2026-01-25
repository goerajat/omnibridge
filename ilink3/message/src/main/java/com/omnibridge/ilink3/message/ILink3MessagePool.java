package com.omnibridge.ilink3.message;

import com.omnibridge.ilink3.message.order.*;
import com.omnibridge.ilink3.message.session.*;
import com.omnibridge.sbe.message.SbeMessagePool;

/**
 * Thread-local pool for iLink 3 message instances.
 * <p>
 * Provides zero-allocation message reuse by maintaining a single instance
 * of each message type per thread.
 */
public class ILink3MessagePool extends SbeMessagePool {

    // Register message factories (static initializer)
    static {
        // Session messages
        registerFactory(NegotiateMessage.class, NegotiateMessage::new);
        registerFactory(NegotiationResponseMessage.class, NegotiationResponseMessage::new);
        registerFactory(EstablishMessage.class, EstablishMessage::new);
        registerFactory(EstablishmentAckMessage.class, EstablishmentAckMessage::new);
        registerFactory(TerminateMessage.class, TerminateMessage::new);
        registerFactory(SequenceMessage.class, SequenceMessage::new);

        // Order entry messages
        registerFactory(NewOrderSingleMessage.class, NewOrderSingleMessage::new);
        registerFactory(OrderCancelRequestMessage.class, OrderCancelRequestMessage::new);
        registerFactory(OrderCancelReplaceRequestMessage.class, OrderCancelReplaceRequestMessage::new);
        registerFactory(ExecutionReportNewMessage.class, ExecutionReportNewMessage::new);
    }

    /**
     * Creates a new message pool for iLink 3.
     */
    public ILink3MessagePool() {
        // Session messages
        registerTemplateId(NegotiateMessage.TEMPLATE_ID, NegotiateMessage.class);
        registerTemplateId(NegotiationResponseMessage.TEMPLATE_ID, NegotiationResponseMessage.class);
        registerTemplateId(EstablishMessage.TEMPLATE_ID, EstablishMessage.class);
        registerTemplateId(EstablishmentAckMessage.TEMPLATE_ID, EstablishmentAckMessage.class);
        registerTemplateId(TerminateMessage.TEMPLATE_ID, TerminateMessage.class);
        registerTemplateId(SequenceMessage.TEMPLATE_ID, SequenceMessage.class);

        // Order entry messages
        registerTemplateId(NewOrderSingleMessage.TEMPLATE_ID, NewOrderSingleMessage.class);
        registerTemplateId(OrderCancelRequestMessage.TEMPLATE_ID, OrderCancelRequestMessage.class);
        registerTemplateId(OrderCancelReplaceRequestMessage.TEMPLATE_ID, OrderCancelReplaceRequestMessage.class);
        registerTemplateId(ExecutionReportNewMessage.TEMPLATE_ID, ExecutionReportNewMessage.class);
    }
}
