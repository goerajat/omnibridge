package com.omnibridge.sbe.message;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thread-local pool for SBE message instances.
 * <p>
 * Provides zero-allocation message reuse by maintaining a single instance
 * of each message type per thread. Messages are flyweights that wrap buffers,
 * so reusing instances avoids object allocation during message processing.
 * <p>
 * Usage pattern:
 * <pre>
 * // Get pooled message instance
 * NewOrderSingleMessage msg = pool.getMessage(NewOrderSingleMessage.class);
 * msg.wrap(buffer, offset);
 * // Use message...
 * // Instance is automatically available for reuse
 *
 * // Or by template ID
 * SbeMessage msg = pool.getMessageByTemplateId(514);
 * </pre>
 * <p>
 * Subclasses should register message factories in a static initializer:
 * <pre>
 * public class ILink3MessagePool extends SbeMessagePool {
 *     static {
 *         registerFactory(NewOrderSingleMessage.class, NewOrderSingleMessage::new);
 *         registerFactory(ExecutionReportMessage.class, ExecutionReportMessage::new);
 *     }
 * }
 * </pre>
 *
 * @see SbeMessage
 * @see SbeMessageFactory
 */
public abstract class SbeMessagePool {

    /** Map of message class to factory for creating new instances */
    private static final Map<Class<? extends SbeMessage>, Supplier<? extends SbeMessage>> FACTORIES =
            new HashMap<>();

    /** Thread-local pool instance */
    private final Map<Class<? extends SbeMessage>, SbeMessage> messageMap = new HashMap<>();

    /** Template ID to message class mapping */
    private final Map<Integer, Class<? extends SbeMessage>> templateIdMap = new HashMap<>();

    /**
     * Registers a message factory for a message class.
     * Call this in static initializers of subclasses.
     *
     * @param messageClass the message class
     * @param factory the factory to create instances
     * @param <T> the message type
     */
    protected static <T extends SbeMessage> void registerFactory(
            Class<T> messageClass, Supplier<T> factory) {
        FACTORIES.put(messageClass, factory);
    }

    /**
     * Registers a template ID to message class mapping.
     * Call this in the constructor of subclasses.
     *
     * @param templateId the template ID
     * @param messageClass the message class
     */
    protected void registerTemplateId(int templateId, Class<? extends SbeMessage> messageClass) {
        templateIdMap.put(templateId, messageClass);
    }

    /**
     * Gets a pooled message instance by class.
     * The message is reset before being returned.
     *
     * @param messageClass the message class
     * @param <T> the message type
     * @return the pooled message instance
     * @throws IllegalArgumentException if the message class is not registered
     */
    @SuppressWarnings("unchecked")
    public <T extends SbeMessage> T getMessage(Class<T> messageClass) {
        SbeMessage msg = messageMap.get(messageClass);
        if (msg == null) {
            msg = createMessage(messageClass);
            messageMap.put(messageClass, msg);
        }
        msg.reset();
        return (T) msg;
    }

    /**
     * Gets a pooled message instance by template ID.
     *
     * @param templateId the template ID
     * @return the pooled message instance, or null if not registered
     */
    public SbeMessage getMessageByTemplateId(int templateId) {
        Class<? extends SbeMessage> messageClass = templateIdMap.get(templateId);
        if (messageClass == null) {
            return null;
        }
        return getMessage(messageClass);
    }

    /**
     * Creates a new (non-pooled) message instance.
     * Use this when you need a message that outlives the current scope.
     *
     * @param messageClass the message class
     * @param <T> the message type
     * @return a new message instance
     * @throws IllegalArgumentException if the message class is not registered
     */
    @SuppressWarnings("unchecked")
    public static <T extends SbeMessage> T createMessage(Class<T> messageClass) {
        Supplier<? extends SbeMessage> factory = FACTORIES.get(messageClass);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "No factory registered for message class: " + messageClass.getName());
        }
        return (T) factory.get();
    }

    /**
     * Checks if a message class is registered.
     *
     * @param messageClass the message class
     * @return true if registered
     */
    public static boolean isRegistered(Class<? extends SbeMessage> messageClass) {
        return FACTORIES.containsKey(messageClass);
    }

    /**
     * Checks if a template ID is registered.
     *
     * @param templateId the template ID
     * @return true if registered
     */
    public boolean supportsTemplateId(int templateId) {
        return templateIdMap.containsKey(templateId);
    }

    /**
     * Releases a message back to the pool.
     * This is a no-op as messages are automatically reused,
     * but provided for API consistency.
     *
     * @param message the message to release
     */
    public void release(SbeMessage message) {
        if (message != null) {
            message.reset();
        }
    }

    /**
     * Clears all cached message instances.
     * Typically called during shutdown or testing.
     */
    public void clear() {
        messageMap.values().forEach(SbeMessage::reset);
        messageMap.clear();
    }

    /**
     * Gets the number of registered message types.
     *
     * @return the count
     */
    public int getRegisteredTypeCount() {
        return templateIdMap.size();
    }
}
