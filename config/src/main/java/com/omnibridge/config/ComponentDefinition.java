package com.omnibridge.config;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a component definition parsed from configuration.
 *
 * <p>Component definitions in HOCON config look like:</p>
 * <pre>{@code
 * components {
 *   clock-provider {
 *     enabled = true
 *     factory = "com.omnibridge.config.factory.ClockProviderFactory"
 *     type = "com.omnibridge.config.ClockProvider"
 *   }
 *   network {
 *     enabled = true
 *     factory = "com.omnibridge.network.factory.NetworkEventLoopFactory"
 *     type = "com.omnibridge.network.NetworkEventLoop"
 *     dependencies = ["clock-provider"]
 *   }
 * }
 * }</pre>
 */
public class ComponentDefinition {

    private static final Logger log = LoggerFactory.getLogger(ComponentDefinition.class);

    private final String name;
    private final boolean enabled;
    private final String factoryClassName;
    private final Class<? extends Component> componentType;
    private final List<String> dependencies;
    private final Config componentConfig;

    private ComponentDefinition(String name, boolean enabled, String factoryClassName,
                                Class<? extends Component> componentType,
                                List<String> dependencies, Config componentConfig) {
        this.name = name;
        this.enabled = enabled;
        this.factoryClassName = factoryClassName;
        this.componentType = componentType;
        this.dependencies = List.copyOf(dependencies);
        this.componentConfig = componentConfig;
    }

    /**
     * Parse a component definition from configuration.
     *
     * @param name the component name (key in config)
     * @param config the component's configuration block
     * @return the parsed component definition
     */
    public static ComponentDefinition fromConfig(String name, Config config) {
        boolean enabled = config.hasPath("enabled") ? config.getBoolean("enabled") : true;
        String factoryClass = config.getString("factory");

        String typeClassName = config.hasPath("type") ?
            config.getString("type") : inferTypeFromFactory(factoryClass);
        Class<? extends Component> componentType = loadComponentType(typeClassName);

        List<String> dependencies = config.hasPath("dependencies") ?
            config.getStringList("dependencies") : List.of();

        return new ComponentDefinition(name, enabled, factoryClass, componentType,
                                       dependencies, config);
    }

    /**
     * Load all component definitions from the root configuration.
     *
     * @param rootConfig the root configuration containing a "components" section
     * @return map of component name to definition, in configuration order
     */
    public static Map<String, ComponentDefinition> loadAll(Config rootConfig) {
        Map<String, ComponentDefinition> result = new LinkedHashMap<>();

        if (!rootConfig.hasPath("components")) {
            return result;
        }

        Config componentsConfig = rootConfig.getConfig("components");

        for (String name : componentsConfig.root().keySet()) {
            try {
                Config componentConfig = componentsConfig.getConfig(name);
                ComponentDefinition def = fromConfig(name, componentConfig);
                result.put(name, def);
                log.debug("Loaded component definition: {} (factory={})", name, def.getFactoryClassName());
            } catch (Exception e) {
                log.error("Failed to load component definition '{}': {}", name, e.getMessage());
                throw new RuntimeException("Failed to load component definition: " + name, e);
            }
        }

        return result;
    }

    /**
     * Infer the component type from the factory class name.
     * Convention: FooBarFactory creates FooBar component.
     */
    private static String inferTypeFromFactory(String factoryClass) {
        if (factoryClass.endsWith("Factory")) {
            return factoryClass.substring(0, factoryClass.length() - "Factory".length());
        }
        throw new IllegalArgumentException(
            "Cannot infer component type from factory class: " + factoryClass +
            ". Please specify 'type' explicitly in the configuration.");
    }

    /**
     * Load the component type class.
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Component> loadComponentType(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            if (!Component.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException(
                    "Type " + className + " does not implement Component interface");
            }
            return (Class<? extends Component>) clazz;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Component type class not found: " + className, e);
        }
    }

    /**
     * Get the component name (key in configuration).
     */
    public String getName() {
        return name;
    }

    /**
     * Check if the component is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get the fully qualified factory class name.
     */
    public String getFactoryClassName() {
        return factoryClassName;
    }

    /**
     * Get the component type class.
     */
    public Class<? extends Component> getComponentType() {
        return componentType;
    }

    /**
     * Get the list of dependency component names.
     */
    public List<String> getDependencies() {
        return dependencies;
    }

    /**
     * Get the component-specific configuration block.
     */
    public Config getComponentConfig() {
        return componentConfig;
    }

    @Override
    public String toString() {
        return "ComponentDefinition{" +
               "name='" + name + '\'' +
               ", enabled=" + enabled +
               ", factory='" + factoryClassName + '\'' +
               ", type=" + componentType.getSimpleName() +
               ", dependencies=" + dependencies +
               '}';
    }
}
