package dev.sorokin.screennavigator;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class ScreenFactory {

    private static final long DEFAULT_WAIT_TIMEOUT_SECONDS = 10;
    private static final ScopedValue<Set<Class<?>>> CREATING = ScopedValue.newInstance();

    private final ConcurrentMap<Class<? extends Screen<?, ?, ?>>, Supplier<Screen<?, ?, ?>>> factories = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<? extends Screen<?, ?, ?>>, CompletableFuture<Screen<?, ?, ?>>> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<? extends Screen<?, ?, ?>>, Screen<?, ?, ?>> instances = new ConcurrentHashMap<>();

    private final long waitTimeoutSeconds;

    public ScreenFactory() {
        this(DEFAULT_WAIT_TIMEOUT_SECONDS);
    }

    public ScreenFactory(long waitTimeoutSeconds) {
        this.waitTimeoutSeconds = waitTimeoutSeconds;
    }

    public <T extends Screen<?, ?, ?>> void register(Class<T> screenType, Supplier<T> factory) {
        factories.put(screenType, factory::get);
    }

    public <T extends Screen<?, ?, ?>> T get(Class<T> screenType) {
        var existing = instances.get(screenType);
        if (existing != null) {
            return screenType.cast(existing);
        }

        checkNotCircular(screenType);
        var creatingFuture = new CompletableFuture<Screen<?, ?, ?>>();
        var raced = pending.putIfAbsent(screenType, creatingFuture);
        if (raced != null) {
            return screenType.cast(awaitCreation(screenType, raced));
        }

        try {
            var created = createInstance(screenType);
            created.onCreate();
            instances.put(screenType, created);
            creatingFuture.complete(created);
            return screenType.cast(created);
        } catch (Throwable t) {
            creatingFuture.completeExceptionally(t);
            throw t;
        } finally {
            pending.remove(screenType, creatingFuture);
        }
    }

    /** Убирает экран из кэша и вызывает {@link ScreenLifecycle#onDestroy()}. Решает проблему #9. */
    public void evict(Class<? extends Screen<?, ?, ?>> screenType) {
        var removed = instances.remove(screenType);
        if (removed != null) {
            removed.onDestroy();
        }
    }

    private Screen<?, ?, ?> createInstance(Class<?> screenType) {
        var factory = factories.get(screenType);
        if (factory == null) {
            throw new IllegalStateException("No factory registered for " + screenType.getName());
        }
        var next = new HashSet<>(CREATING.orElse(Set.of()));
        next.add(screenType);
        try {
            return ScopedValue.where(CREATING, Set.copyOf(next)).call(factory::get);
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
    }

    private void checkNotCircular(Class<?> screenType) {
        if (CREATING.orElse(Set.of()).contains(screenType)) {
            throw new IllegalStateException("Circular dependency detected for: " + screenType.getName());
        }
    }

    private Screen<?, ?, ?> awaitCreation(Class<?> screenType, CompletableFuture<Screen<?, ?, ?>> future) {
        try {
            return future.get(waitTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "Timed out waiting for " + screenType.getName() + " after " + waitTimeoutSeconds + "s", e);
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case RuntimeException re -> throw re;
                case Error error -> throw error;
                default -> throw new RuntimeException(e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + screenType.getName(), e);
        }
    }
}