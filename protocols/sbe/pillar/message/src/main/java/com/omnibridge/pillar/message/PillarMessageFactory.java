package com.omnibridge.pillar.message;

import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.sbe.message.SbeMessageFactory;
import com.omnibridge.sbe.message.SbeMessagePool;
import com.omnibridge.sbe.message.SbeMessageType;
import com.omnibridge.sbe.message.SbeSchema;
import org.agrona.DirectBuffer;

/**
 * Factory for creating and reading NYSE Pillar messages.
 */
public class PillarMessageFactory implements SbeMessageFactory {

    private static final ThreadLocal<PillarMessagePool> POOL =
            ThreadLocal.withInitial(PillarMessagePool::new);

    private static final ThreadLocal<PillarMessageReader> READER =
            ThreadLocal.withInitial(PillarMessageReader::new);

    @Override
    public SbeSchema getSchema() {
        return PillarSchema.INSTANCE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends SbeMessage> T getMessage(Class<T> messageClass) {
        if (PillarMessage.class.isAssignableFrom(messageClass)) {
            return (T) POOL.get().getMessage((Class<? extends PillarMessage>) messageClass);
        }
        return null;
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
        return PillarMessageType.fromTemplateId(templateId);
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
