package com.omnibridge.config.provider;

import com.omnibridge.config.*;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link Singleton}-annotated component types are created at most once
 * regardless of the lookup key used.
 */
class SingletonComponentTest {

    private DefaultComponentProvider provider;

    // ---- test component types ----

    @Singleton
    static class TestSingletonComponent implements Component {
        private final String createdWithName;

        TestSingletonComponent(String name) {
            this.createdWithName = name;
        }

        @Override public void initialize() {}
        @Override public void startActive() {}
        @Override public void startStandby() {}
        @Override public void becomeActive() {}
        @Override public void becomeStandby() {}
        @Override public void stop() {}
        @Override public String getName() { return "test-singleton"; }
        @Override public ComponentState getState() { return ComponentState.ACTIVE; }
    }

    static class TestMultiComponent implements Component {
        private final String createdWithName;

        TestMultiComponent(String name) {
            this.createdWithName = name;
        }

        @Override public void initialize() {}
        @Override public void startActive() {}
        @Override public void startStandby() {}
        @Override public void becomeActive() {}
        @Override public void becomeStandby() {}
        @Override public void stop() {}
        @Override public String getName() { return "test-multi"; }
        @Override public ComponentState getState() { return ComponentState.ACTIVE; }
    }

    @BeforeEach
    void setUp() {
        provider = DefaultComponentProvider.create(ConfigFactory.empty());
        provider.register(TestSingletonComponent.class,
            (name, config, p) -> new TestSingletonComponent(name));
        provider.register(TestMultiComponent.class,
            (name, config, p) -> new TestMultiComponent(name));
    }

    @Test
    void unnamedFirstThenNamedReturnsSameInstance() {
        TestSingletonComponent first = provider.getComponent(TestSingletonComponent.class);
        TestSingletonComponent second = provider.getComponent("foo", TestSingletonComponent.class);

        assertSame(first, second, "Named lookup must return the unnamed singleton");
    }

    @Test
    void namedFirstThenUnnamedReturnsSameInstance() {
        TestSingletonComponent first = provider.getComponent("bar", TestSingletonComponent.class);
        TestSingletonComponent second = provider.getComponent(TestSingletonComponent.class);

        assertSame(first, second, "Unnamed lookup must return the named singleton");
    }

    @Test
    void namedFirstThenDifferentNameReturnsSameInstance() {
        TestSingletonComponent first = provider.getComponent("alpha", TestSingletonComponent.class);
        TestSingletonComponent second = provider.getComponent("beta", TestSingletonComponent.class);

        assertSame(first, second, "Different named lookups must return the same singleton");
    }

    @Test
    void nonSingletonNamedAndUnnamedCreateSeparateInstances() {
        TestMultiComponent unnamed = provider.getComponent(TestMultiComponent.class);
        TestMultiComponent named = provider.getComponent("x", TestMultiComponent.class);

        assertNotSame(unnamed, named,
            "Non-singleton type should create separate instances for different keys");
    }

    @Test
    void lifecycleContainsSingletonOnlyOnce() {
        provider.getComponent(TestSingletonComponent.class);
        provider.getComponent("a", TestSingletonComponent.class);
        provider.getComponent("b", TestSingletonComponent.class);

        long count = provider.getLifeCycle().getComponents().stream()
            .filter(c -> c instanceof TestSingletonComponent)
            .count();

        assertEquals(1, count, "Singleton must appear in lifecycle list exactly once");
    }
}
