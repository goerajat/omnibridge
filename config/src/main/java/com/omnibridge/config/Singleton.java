package com.omnibridge.config;

import java.lang.annotation.*;

/**
 * Marks a {@link Component} type as a singleton within a {@link com.omnibridge.config.provider.DefaultComponentProvider}.
 *
 * <p>When a component type is annotated with {@code @Singleton}, the provider guarantees that at most
 * one instance of that type exists, regardless of whether it is looked up by name, by type, or both.
 * Without this annotation, a named lookup and an unnamed lookup may each trigger a separate factory call,
 * resulting in duplicate instances.</p>
 *
 * <p>Apply this to infrastructure components that must not be duplicated (clocks, schedulers,
 * network event loops, admin servers, metrics, etc.). Do <em>not</em> apply it to protocol engine
 * types where multiple named instances are expected.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Singleton {
}
