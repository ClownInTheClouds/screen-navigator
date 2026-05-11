package dev.sorokin.screennavigator;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public class ScreenFactory {

    private final ConcurrentMap<Class<? extends Screen<?, ?, ?>>, Supplier<Screen<?, ?, ?>>> factories = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<? extends Screen<?, ?, ?>>, Screen<?, ?, ?>> instances = new ConcurrentHashMap<>();

    private final ConcurrentMap<Class<?>, Object> locks = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<Class<?>>> creating = ThreadLocal.withInitial(HashSet::new);

    public <T extends Screen<?, ?, ?>> void register(Class<T> screenType, Supplier<T> factory) {
        factories.put(screenType, factory::get);
    }

    public <T extends Screen<?, ?, ?>> Screen<?, ?, ?> get(Class<T> screenType) {
        var instance = instances.get(screenType);
        if (instance != null) {
            return screenType.cast(instance);
        }
        var lock = locks.computeIfAbsent(screenType, _ -> new Object());
        synchronized (lock) {
            instance = instances.get(screenType);
            if (instance == null) {
                instance = createInstance(screenType);
                instances.put(screenType, instance);
            }
            return screenType.cast(instance);
        }
    }

    private Screen<?, ?, ?> createInstance(Class<?> screenType) {
        var current = creating.get();
        if (!current.add(screenType)) {
            throw new IllegalStateException("Circular dependency detected for: " + screenType.getName());
        }
        try {
            var factory = factories.get(screenType);
            if (factory == null) {
                throw new IllegalStateException("No factory registered for " + screenType.getName());
            }
            return factory.get();
        } finally {
            current.remove(screenType);
        }
    }
}
