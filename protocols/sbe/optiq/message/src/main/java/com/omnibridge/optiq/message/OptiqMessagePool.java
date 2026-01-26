package com.omnibridge.optiq.message;

import com.omnibridge.optiq.message.order.*;
import com.omnibridge.optiq.message.session.*;
import com.omnibridge.sbe.message.SbeMessagePool;

/**
 * Thread-local pool for Optiq message instances.
 * <p>
 * Provides zero-allocation message reuse by maintaining a single instance
 * of each message type per thread.
 */
public class OptiqMessagePool extends SbeMessagePool {

    // Register message factories (static initializer)
    static {
        // Session messages
        registerFactory(LogonMessage.class, LogonMessage::new);
        registerFactory(LogonAckMessage.class, LogonAckMessage::new);
        registerFactory(LogoutMessage.class, LogoutMessage::new);
        registerFactory(HeartbeatMessage.class, HeartbeatMessage::new);

        // Order entry messages
        registerFactory(NewOrderMessage.class, NewOrderMessage::new);
        registerFactory(ModifyOrderMessage.class, ModifyOrderMessage::new);
        registerFactory(CancelOrderMessage.class, CancelOrderMessage::new);
        registerFactory(ExecutionReportMessage.class, ExecutionReportMessage::new);
        registerFactory(RejectMessage.class, RejectMessage::new);
    }

    /**
     * Creates a new message pool for Optiq.
     */
    public OptiqMessagePool() {
        // Session messages
        registerTemplateId(LogonMessage.TEMPLATE_ID, LogonMessage.class);
        registerTemplateId(LogonAckMessage.TEMPLATE_ID, LogonAckMessage.class);
        registerTemplateId(LogoutMessage.TEMPLATE_ID, LogoutMessage.class);
        registerTemplateId(HeartbeatMessage.TEMPLATE_ID, HeartbeatMessage.class);

        // Order entry messages
        registerTemplateId(NewOrderMessage.TEMPLATE_ID, NewOrderMessage.class);
        registerTemplateId(ModifyOrderMessage.TEMPLATE_ID, ModifyOrderMessage.class);
        registerTemplateId(CancelOrderMessage.TEMPLATE_ID, CancelOrderMessage.class);
        registerTemplateId(ExecutionReportMessage.TEMPLATE_ID, ExecutionReportMessage.class);
        registerTemplateId(RejectMessage.TEMPLATE_ID, RejectMessage.class);
    }
}
