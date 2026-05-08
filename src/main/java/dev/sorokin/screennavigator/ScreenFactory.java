package dev.sorokin.screennavigator;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ScreenFactory {

    private final Map<Class<? extends Screen>, Supplier<Screen>> factories = new HashMap<>();
    private final Map<Class<? extends Screen>, Screen> instances = new HashMap<>();

    public <T extends Screen> void register(Class<T> screenType, Supplier<T> factory) {
        factories.put(screenType, factory::get);
    }

    public <T extends Screen> Screen get(Class<T> screenType) {
        return instances.computeIfAbsent(screenType, type -> {
            var factory = factories.get(type);
            if (factory == null) {
                throw new IllegalStateException("No factory registered for: " + type.getName());
            }
            return factory.get();
        });
    }
}
