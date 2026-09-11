package dev.sorokin.screennavigator;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Потокобезопасная кэширующая фабрика экранов.
 * <p>
 * Каждый {@link Screen} регистрируется один раз через {@link #register} (создание в один
 * шаг, на потоке вызывающего) или {@link #registerAsync} (создание в две фазы: фоновая
 * подготовка данных без обращения к toolkit UI + сборка экрана на UI-потоке) и затем
 * лениво создаётся не более одного раза — повторный {@link #get} возвращает закэшированный
 * экземпляр.
 */
public class ScreenFactory {

    private static final long DEFAULT_WAIT_TIMEOUT_SECONDS = 10;

    /**
     * Instance-level (не {@code static}): состояние "какие типы сейчас создаются" привязано
     * к конкретному экземпляру {@code ScreenFactory}, а не расшарено между всеми фабриками
     * приложения. {@link ScopedValue} не обязан быть {@code static} — единственное требование
     * JEP 506 — это {@code final} поле.
     */
    private final ScopedValue<Set<Class<?>>> creating = ScopedValue.newInstance();

    private final ConcurrentMap<Class<? extends Screen<?, ?, ?>>, ScreenSource> factories = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<? extends Screen<?, ?, ?>>, CompletableFuture<Screen<?, ?, ?>>> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<? extends Screen<?, ?, ?>>, Screen<?, ?, ?>> instances = new ConcurrentHashMap<>();

    private final long waitTimeoutSeconds;

    public ScreenFactory() {
        this(DEFAULT_WAIT_TIMEOUT_SECONDS);
    }

    public ScreenFactory(long waitTimeoutSeconds) {
        this.waitTimeoutSeconds = waitTimeoutSeconds;
    }

    /**
     * Регистрирует фабрику, создающую экран в один шаг. Используется для обычного
     * (синхронного) {@code show(Class)}. Если тип впоследствии показывается через
     * {@code showAsync(Class, Executor)}, вся сборка (включая конструктор {@code Screen}
     * и, как следствие, его toolkit-view) гарантированно выполняется на UI-потоке —
     * см. {@link #buildOnUiThread(Class, Object)} — фоновый поток в этом случае лишь
     * "прокачивает" тривиальный no-op prepare.
     */
    public <T extends Screen<?, ?, ?>> void register(Class<T> screenType, Supplier<T> factory) {
        factories.put(screenType, new ScreenSource(() -> null, ignored -> factory.get()));
    }

    /**
     * Регистрирует экран в две явные фазы для потокобезопасного {@code showAsync}:
     * <ul>
     *   <li>{@code backgroundPrepare} — тяжёлая часть (I/O, вычисления, загрузка данных).
     *       Выполняется на фоновом {@code Executor}, переданном в
     *       {@link ScreenNavigator#showAsync}. НИКОГДА не должен создавать или трогать
     *       toolkit UI-объекты ({@code JComponent}, {@code Node}, ...).</li>
     *   <li>{@code uiFactory} — собственно конструирование {@link Screen} из результата
     *       {@code backgroundPrepare}. Единственная фаза, где допустимо строить
     *       toolkit-специфичный view — она гарантированно выполняется на UI-потоке
     *       (EDT/FX Application Thread) при показе через {@code showAsync}.</li>
     * </ul>
     * Для обычного синхронного {@code show(Class)}/{@link #get(Class)} обе фазы
     * выполняются последовательно на потоке вызывающего — разделение фаз не мешает
     * синхронному пути, а только защищает асинхронный.
     *
     * @param backgroundPrepare подготовка данных без обращения к UI toolkit'а
     * @param uiFactory          сборка {@code Screen} (и его view) из подготовленных данных
     */
    public <T extends Screen<?, ?, ?>, D> void registerAsync(
            Class<T> screenType, Supplier<D> backgroundPrepare, Function<D, T> uiFactory) {
        Supplier<Object> erasedPrepare = backgroundPrepare::get;
        @SuppressWarnings("unchecked")
        Function<Object, Screen<?, ?, ?>> erasedBuild = data -> uiFactory.apply((D) data);
        factories.put(screenType, new ScreenSource(erasedPrepare, erasedBuild));
    }

    public <T extends Screen<?, ?, ?>> T get(Class<T> screenType) {
        return obtain(screenType, () -> sourceOf(screenType).prepare().get());
    }

    /**
     * Фаза 1 асинхронного создания (см. {@link #registerAsync}): выполняет
     * зарегистрированный {@code backgroundPrepare} и возвращает подготовленные данные,
     * ничего не строя в toolkit UI. Вызывающая сторона ({@link AbstractScreenNavigator})
     * обязана вызывать этот метод на фоновом потоке, а результат передать в
     * {@link #buildOnUiThread(Class, Object)}, вызванный уже на UI-потоке.
     * <p>
     * Если экран уже закэширован, ничего не готовит и возвращает {@code null} —
     * {@link #buildOnUiThread} в этом случае просто вернёт закэшированный экземпляр,
     * не трогая {@code uiFactory} повторно.
     *
     * @throws IllegalStateException если для типа не зарегистрирована фабрика,
     *                                либо обнаружена циклическая зависимость создания
     */
    public Object prepareAsync(Class<? extends Screen<?, ?, ?>> screenType) {
        if (instances.containsKey(screenType)) {
            return null;
        }
        checkNotCircular(screenType);
        return sourceOf(screenType).prepare().get();
    }

    /**
     * Фаза 2 асинхронного создания: строит экран (или возвращает уже закэшированный)
     * из данных, подготовленных {@link #prepareAsync}. Единственное место, где для
     * async-показа допустимо конструировать toolkit-компоненты — вызывающая сторона
     * обязана гарантировать, что это происходит на UI-потоке.
     * <p>
     * Как и {@link #get}, безопасен при гонке с параллельными {@code get}/{@code prepareAsync}
     * для того же типа — использует тот же {@code pending}/{@code instances} механизм.
     */
    public <T extends Screen<?, ?, ?>> T buildOnUiThread(Class<T> screenType, Object preparedData) {
        return obtain(screenType, () -> preparedData);
    }

    /**
     * Общая точка входа для создания экрана с защитой от дублирующего создания при
     * гонке нескольких потоков за один и тот же {@code screenType} ({@code pending} +
     * {@code CompletableFuture}), используемая как синхронным {@link #get}, так и
     * фазой 2 асинхронного пути {@link #buildOnUiThread}. {@code preparedDataSupplier}
     * вызывается не более одного раза — только победителем гонки.
     */
    private <T extends Screen<?, ?, ?>> T obtain(Class<T> screenType, Supplier<Object> preparedDataSupplier) {
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
            var source = sourceOf(screenType);
            var created = createAndInitialize(screenType, source, preparedDataSupplier.get());
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

    /**
     * Конструирует экран через {@code source.build()} и вызывает его
     * {@link ScreenLifecycle#onCreate()} в пределах одной {@link ScopedValue} области
     * {@code creating} — это гарантирует, что реентерабельный self-fetch того же
     * {@code screenType} внутри {@code onCreate()} (на том же потоке) детектируется как
     * циклическая зависимость, а не приводит к синхронному самоожиданию потока.
     */
    private Screen<?, ?, ?> createAndInitialize(Class<?> screenType, ScreenSource source, Object preparedData) {
        var next = new HashSet<>(creating.orElse(Set.of()));
        next.add(screenType);
        try {
            return ScopedValue.where(creating, Set.copyOf(next)).call(() -> {
                var instance = source.build().apply(preparedData);
                instance.onCreate();
                return instance;
            });
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
    }

    /**
     * Убирает экран из кэша и вызывает {@link ScreenLifecycle#onDestroy()}.
     */
    public void evict(Class<? extends Screen<?, ?, ?>> screenType) {
        var removed = instances.remove(screenType);
        if (removed != null) {
            removed.onDestroy();
        }
    }

    /**
     * Проверяет, зарегистрирована ли фабрика для указанного типа экрана
     * через {@link #register}/{@link #registerAsync} — без попытки создать экземпляр.
     * Полезно для динамического построения меню/навигации по зарегистрированным экранам.
     */
    public boolean isRegistered(Class<? extends Screen<?, ?, ?>> screenType) {
        return factories.containsKey(screenType);
    }

    /**
     * Эвиктит (вызывает {@link ScreenLifecycle#onDestroy()} и удаляет из кэша)
     * все закэшированные экземпляры экранов разом. Регистрация фабрик через
     * {@link #register}/{@link #registerAsync} не затрагивается — экраны будут
     * пересозданы при следующем {@link #get(Class)}.
     * <p>
     * Основной сценарий — logout/сброс состояния приложения без ручного
     * перебора всех зарегистрированных {@code Class}.
     */
    public void clear() {
        for (var screenType : Set.copyOf(instances.keySet())) {
            evict(screenType);
        }
    }

    private ScreenSource sourceOf(Class<?> screenType) {
        var source = factories.get(screenType);
        if (source == null) {
            throw new IllegalStateException("No factory registered for " + screenType.getName());
        }
        return source;
    }

    private void checkNotCircular(Class<?> screenType) {
        if (creating.orElse(Set.of()).contains(screenType)) {
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

    /**
     * Внутреннее (type-erased) представление зарегистрированной фабрики: пара
     * "фоновая подготовка данных" + "сборка экрана из данных". Для {@link #register}
     * {@code prepare} — тривиальный no-op ({@code () -> null}), вся работа в {@code build}.
     */
    private record ScreenSource(Supplier<Object> prepare, Function<Object, Screen<?, ?, ?>> build) {
    }
}
