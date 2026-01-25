package com.omnibridge.ilink3.message;

import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.sbe.message.SbeMessageFactory;
import com.omnibridge.sbe.message.SbeMessagePool;
import com.omnibridge.sbe.message.SbeMessageType;
import com.omnibridge.sbe.message.SbeSchema;
import org.agrona.DirectBuffer;

/**
 * Factory for creating and reading iLink 3 messages.
 */
public class ILink3MessageFactory implements SbeMessageFactory {

    private static final ThreadLocal<ILink3MessagePool> POOL =
            ThreadLocal.withInitial(ILink3MessagePool::new);

    private static final ThreadLocal<ILink3MessageReader> READER =
            ThreadLocal.withInitial(ILink3MessageReader::new);

    @Override
    public SbeSchema getSchema() {
        return ILink3Schema.INSTANCE;
    }

    @Override
    public <T extends SbeMessage> T getMessage(Class<T> messageClass) {
        return POOL.get().getMessage(messageClass);
    }

    @Override
    public SbeMessage getMessageByTemplateId(int templateId) {
        return POOL.get().getMessageByTemplateId(templateId);
    }

    @Override
    public SbeMessage readMessage(DirectBuffer buffer, int offset, int length) {
        return READER.get().read(buffer, offset, length);
    }

    @Override
    public int peekTemplateId(DirectBuffer buffer, int offset) {
        return READER.get().peekTemplateId(buffer, offset);
    }

    @Override
    public int getExpectedLength(DirectBuffer buffer, int offset, int availableLength) {
        return READER.get().getExpectedLength(buffer, offset, availableLength);
    }

    @Override
    public SbeMessageType getMessageType(int templateId) {
        return ILink3MessageType.fromTemplateId(templateId);
    }

    @Override
    public boolean supportsTemplateId(int templateId) {
        return POOL.get().supportsTemplateId(templateId);
    }

    @Override
    public <T extends SbeMessage> T createMessage(Class<T> messageClass) {
        return SbeMessagePool.createMessage(messageClass);
    }

    @Override
    public void release(SbeMessage message) {
        POOL.get().release(message);
    }
}
