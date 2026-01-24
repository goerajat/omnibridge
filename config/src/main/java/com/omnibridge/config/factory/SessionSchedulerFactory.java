package com.omnibridge.config.factory;

import com.omnibridge.config.ClockProvider;
import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.config.schedule.SchedulerConfig;
import com.omnibridge.config.schedule.SessionScheduler;
import com.typesafe.config.Config;

/**
 * Factory for creating {@link SessionScheduler} instances.
 *
 * <p>Creates a session scheduler using the clock provider from the component provider.
 * If a schedulers section is present in the config, it applies the scheduler configuration.</p>
 */
public class SessionSchedulerFactory implements ComponentFactory<SessionScheduler> {

    @Override
    public SessionScheduler create(String name, Config config, ComponentProvider provider) {
        ClockProvider clock = provider.getComponent(ClockProvider.class);
        SessionScheduler scheduler = new SessionScheduler(clock);

        if (config.hasPath("schedulers")) {
            SchedulerConfig schedulerConfig = SchedulerConfig.fromConfig(config);
            schedulerConfig.applyTo(scheduler);
        }

        return scheduler;
    }
}
