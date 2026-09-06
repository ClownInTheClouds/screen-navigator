package dev.sorokin.screennavigator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Toolkit-agnostic часть навигации: реестр показанных экранов, back-стек, модальные окна,
 * события навигации и диспетчеризация лайфцикл-колбэков. Конкретный тулкит реализует только то,
 * что реально трогает UI-дерево фреймворка: {@link #attach}, {@link #display},
 * {@link #createModal} и {@link #runOnUiThread}.
 *
 * @param <V> тип view конкретного тулкита ({@code JComponent}, {@code Parent}, ...)
 */
public abstract class AbstractScreenNavigator<V> implements ScreenNavigator {

    private static final int UNLIMITED_HISTORY = Integer.MAX_VALUE;

    private final Class<V> viewType;
    private final Map<Class<? extends Screen<?, ?, ?>>, Screen<?, ?, ?>> attached = new HashMap<>();
    private final Deque<Class<? extends Screen<?, ?, ?>>> history = new ArrayDeque<>();
    private final List<ScreenNavigatorListener> listeners = new CopyOnWriteArrayList<>();
    private final Set<Class<? extends Screen<?, ?, ?>>> modalOnly = ConcurrentHashMap.newKeySet();
    private final ScreenFactory screenFactory;
    private final int maxHistoryDepth;

    private Class<? extends Screen<?, ?, ?>> currentScreenType;
    private Screen<?, ?, ?> currentScreen;

    protected AbstractScreenNavigator(Class<V> viewType, ScreenFactory screenFactory, int maxHistoryDepth) {
        if (maxHistoryDepth <= 0) {
            throw new IllegalArgumentException("maxHistoryDepth must be positive, got: " + maxHistoryDepth);
        }
        this.viewType = viewType;
        this.screenFactory = screenFactory;
        this.maxHistoryDepth = maxHistoryDepth;
    }

    /**
     * Создаёт навигатор с новым {@link ScreenFactory} по умолчанию (с таймаутом
     * ожидания {@code 10} секунд — см. {@link ScreenFactory#ScreenFactory()}).
     *
     * @param viewType класс toolkit-специфичного view (например, {@code JComponent.class}
     *                 или {@code Parent.class}), используется для безопасного каста
     *                 в {@link #viewOf(Screen)}
     */
    protected AbstractScreenNavigator(Class<V> viewType) {
        this(viewType, new ScreenFactory(), UNLIMITED_HISTORY);
    }

    /**
     * @param maxHistoryDepth максимальное число экранов, хранимых в истории
     *                         {@link #back()}; при превышении самые старые
     *                         записи вытесняются. Подстраховка от неограниченного
     *                         роста истории в приложениях с очень длинной
     *                         навигацией без вызовов {@code back()}.
     */
    protected AbstractScreenNavigator(Class<V> viewType, int maxHistoryDepth) {
        this(viewType, new ScreenFactory(), maxHistoryDepth);
    }

    /**
     * Создаёт навигатор с явно переданным {@link ScreenFactory}.
     * <p>
     * Основное назначение — тестируемость: тестовый двойник {@code ScreenFactory}
     * (или экземпляр с нестандартным {@code waitTimeoutSeconds}) можно подставить
     * без прохождения полного цикла {@link #install}. Приложениям, использующим
     * навигатор напрямую, обычно достаточно однопараметрического конструктора.
     *
     * @param viewType      класс toolkit-специфичного view
     * @param screenFactory фабрика экранов, используемая этим навигатором
     */
    protected AbstractScreenNavigator(Class<V> viewType, ScreenFactory screenFactory) {
        this(viewType, screenFactory, UNLIMITED_HISTORY);
    }

    @Override
    public void install(SceneConfigurer... configurers) {
        if (configurers == null) return;
        for (var config : configurers) config.configure(screenFactory);
    }

    @Override
    public <T extends Screen<?, ?, ?>> void show(Class<T> screenType) {
        var screen = screenFactory.get(screenType);
        present(screenType, screen, true);
    }

    @Override
    public <SD, T extends Screen<?, ?, SD>> void show(Class<T> screenType, SD data) {
        var screen = screenFactory.get(screenType);
        presentWithData(screenType, screen, data, true);
    }

    @Override
    public <T extends Screen<?, ?, ?>> void showAsync(Class<T> screenType, Executor backgroundExecutor) {
        backgroundExecutor.execute(() -> {
            var screen = screenFactory.get(screenType); // тяжёлое создание — не на UI-потоке
            runOnUiThread(() -> present(screenType, screen, true));
        });
    }

    @Override
    public <SD, T extends Screen<?, ?, SD>> void showAsync(Class<T> screenType, SD data, Executor backgroundExecutor) {
        backgroundExecutor.execute(() -> {
            var screen = screenFactory.get(screenType);
            runOnUiThread(() -> presentWithData(screenType, screen, data, true));
        });
    }

    @Override
    public <T extends Screen<?, ?, ?>> Runnable showModal(Class<T> screenType) {
        var screen = screenFactory.get(screenType);
        return presentModal(screenType, screen);
    }

    @Override
    public <SD, T extends Screen<?, ?, SD>> Runnable showModal(Class<T> screenType, SD data) {
        var screen = screenFactory.get(screenType);
        return presentModalWithData(screenType, screen, data);
    }

    @Override
    public boolean back() {
        requireUiThread();
        if (history.isEmpty()) return false;
        var previousType = history.pop();
        showByCapturedType(previousType, false);
        return true;
    }

    @Override
    public Screen<?, ?, ?> getCurrentScreen() {
        return currentScreen;
    }

    /**
     * Полностью удаляет экран: отсоединяет его view от контейнера тулкита, инвалидирует
     * кэшированный экземпляр в {@link ScreenFactory} и вызывает {@link ScreenLifecycle#onDestroy()}.
     * <p>
     * Если удаляемый экран является текущим показанным ({@link #getCurrentScreen()}),
     * перед уничтожением ему гарантированно доставляется {@link ScreenLifecycle#onHide()} —
     * контракт лайфцикла {@code onCreate -> onShow -> onHide -> onDestroy} соблюдается
     * даже при принудительном evict'е, а не только при обычной навигации через {@link #show}.
     *
     * @param screenType тип экрана; должен быть предварительно зарегистрирован через
     *                   {@link SceneConfigurer#configure(ScreenFactory)}
     * @throws IllegalStateException если вызвано не из UI-потока
     */
    @Override
    public void evict(Class<? extends Screen<?, ?, ?>> screenType) {
        requireUiThread();
        var screen = attached.remove(screenType);
        if (screen != null) {
            detach(screenType, viewOf(screen));
        }
        if (screenType.equals(currentScreenType)) {
            if (currentScreen != null) {
                currentScreen.onHide();
                fire(listener -> listener.onScreenHidden(currentScreenType));
            }
            currentScreen = null;
            currentScreenType = null;
        }
        history.remove(screenType);
        modalOnly.remove(screenType);
        screenFactory.evict(screenType);
        fire(listener -> listener.onScreenDestroyed(screenType));
    }

    private void requireUiThread() {
        if (!isUiThread()) {
            throw new IllegalStateException(
                    "ScreenNavigator must be used from the UI thread; "
                            + "wrap the call in the toolkit's UI dispatch mechanism (SwingUtilities.invokeLater / Platform.runLater)");
        }
    }

    @Override
    public void addListener(ScreenNavigatorListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(ScreenNavigatorListener listener) {
        listeners.remove(listener);
    }

    /**
     * Вспомогательный generic-метод, чтобы вызвать {@link #present} для типа, извлечённого из
     * {@code history} (там он хранится как {@code Class<? extends Screen<?, ?, ?>>} —
     * захваченный wildcard). Приведения типа не требуется: компилятор выполняет
     * wildcard capture при вызове generic-метода с wildcard-аргументом.
     */
    private <T extends Screen<?, ?, ?>> void showByCapturedType(Class<T> screenType, boolean pushHistory) {
        var screen = screenFactory.get(screenType);
        present(screenType, screen, pushHistory);
    }

    /**
     * Показ без scene-данных (обычный {@code show(Class)}/{@code back()}).
     */
    private <T extends Screen<?, ?, ?>> void present(Class<T> screenType, T screen, boolean pushHistory) {
        presentInternal(screenType, screen, pushHistory, () -> {
        });
    }

    /**
     * Показ со scene-данными. Отдельное (не перегруженное) имя — намеренно: перегрузка
     * generic-метода, различающаяся типом параметра-небаундед generic ({@code SD}) против
     * конкретного функционального интерфейса ({@code Runnable}) на той же позиции аргумента,
     * приводит к ambiguous method call при передаче лямбды (см. историю фикса — компилятор не
     * может однозначно разрешить вызов между двумя такими перегрузками). Разные имена методов
     * убирают эту категорию ошибок полностью.
     *
     * <p>Доставка данных ({@link #deliverSceneData}) выполняется как часть общего конвейера показа
     * — на UI-потоке, после {@link #display}, непосредственно перед {@link ScreenLifecycle#onShow()}.
     */
    private <SD, T extends Screen<?, ?, SD>> void presentWithData(Class<T> screenType, T screen, SD data, boolean pushHistory) {
        presentInternal(screenType, screen, pushHistory, () -> deliverSceneData(screen, data));
    }

    /**
     * Общий конвейер показа; {@code deliverSceneData} — no-op для варианта без данных.
     */

    private <T extends Screen<?, ?, ?>> void presentInternal(Class<T> screenType, T screen, boolean pushHistory, Runnable deliverSceneData) {
        requireUiThread();
        if (modalOnly.contains(screenType)) {
            throw new IllegalStateException(
                    screenType.getName() + " is already used as a modal screen; "
                            + "a Screen instance must not be shown via both show(...) and showModal(...)");
        }

        boolean firstShow = !attached.containsKey(screenType);
        if (firstShow) {
            attached.put(screenType, screen);
            attach(screenType, viewOf(screen));
            fire(listener -> listener.onScreenCreated(screenType));
        }

        var previousScreen = currentScreen;
        var previousScreenType = currentScreenType;

        display(screenType, viewOf(screen));

        currentScreen = screen;
        currentScreenType = screenType;
        if (pushHistory && previousScreen != null) {
            history.push(previousScreenType);
            while (history.size() > maxHistoryDepth) {
                history.removeLast();
            }
        }

        if (previousScreen != null) {
            previousScreen.onHide();
            fire(listener -> listener.onScreenHidden(previousScreenType));
        }
        deliverSceneData.run();
        screen.onShow();
        fire(listener -> listener.onScreenShown(screenType));
    }

    private <T extends Screen<?, ?, ?>> Runnable presentModal(Class<T> screenType, T screen) {
        return presentModalInternal(screenType, screen, () -> {
        });
    }

    /**
     * Причина отдельного имени (вместо перегрузки) — та же, что и у {@link #presentWithData}.
     */
    private <SD, T extends Screen<?, ?, SD>> Runnable presentModalWithData(Class<T> screenType, T screen, SD data) {
        return presentModalInternal(screenType, screen, () -> deliverSceneData(screen, data));
    }

    private <T extends Screen<?, ?, ?>> Runnable presentModalInternal(Class<T> screenType, T screen, Runnable deliverSceneData) {
        requireUiThread();
        if (attached.containsKey(screenType)) {
            throw new IllegalStateException(
                    screenType.getName() + " is already attached via show(...); "
                            + "a Screen instance must not be shown via both show(...) and showModal(...)");
        }
        modalOnly.add(screenType);

        var handle = createModal(screenType, viewOf(screen));
        var closed = new AtomicBoolean(false);
        Runnable closeAction = () -> {
            if (closed.compareAndSet(false, true)) handle.close();
        };
        if (screen instanceof ModalScreen modalScreen) modalScreen.bindCloseAction(closeAction);
        fire(listener -> listener.onModalOpened(screenType));

        try {
            deliverSceneData.run();
            screen.onShow();
        } catch (RuntimeException e) {
            closeAction.run();
            throw e;
        }

        handle.show();
        closeAction.run();
        screen.onHide();
        fire(listener -> listener.onModalClosed(screenType));
        return closeAction;
    }

    /**
     * Доставляет scene-данные экрану. Если экран реализует {@link SceneDataAware}, данные приходят
     * как параметр конкретного вызова показа ({@link SceneDataAware#onShow}). Иначе — fallback на
     * старый механизм {@link Screen#sceneData(Object)} (общее мутируемое поле), для обратной
     * совместимости с экранами, которые ещё не мигрировали на {@link SceneDataAware}.
     */
    private <SD> void deliverSceneData(Screen<?, ?, SD> screen, SD data) {
        if (screen instanceof SceneDataAware<?> aware) {
            @SuppressWarnings("unchecked")
            var typed = (SceneDataAware<SD>) aware;
            typed.onShow(data);
        } else {
            screen.sceneData(data);
        }
    }

    /**
     * Уведомляет всех зарегистрированных {@link ScreenNavigatorListener} о событии.
     * <p>
     * Исключение из одного listener'а не прерывает уведомление остальных — иначе
     * порядок регистрации listener'ов начинает влиять на то, кто вообще получит
     * событие, что делает поведение непредсказуемым. По умолчанию исключение
     * пробрасывается дальше через {@link #handleListenerError} (fail-fast), но
     * только после того, как остальные listener'ы уже уведомлены.
     */
    private void fire(Consumer<ScreenNavigatorListener> event) {
        RuntimeException firstFailure = null;
        for (var listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        if (firstFailure != null) {
            handleListenerError(firstFailure);
        }
    }

    /**
     * Проверяет, есть ли в истории навигации экран, к которому можно вернуться
     * вызовом {@link #back()}, без выполнения самого перехода.
     */
    public boolean canGoBack() {
        requireUiThread();
        return !history.isEmpty();
    }

    /**
     * Проверяет, является ли экран указанного типа текущим показанным
     * (эквивалент {@code screenType.equals(currentScreenType)}, но без
     * необходимости обращаться к {@link #getCurrentScreen()} и делать
     * {@code instanceof}/{@code getClass().equals(...)} в коде приложения).
     */
    public boolean isShowing(Class<?> screenType) {
        requireUiThread();
        return currentScreenType != null && currentScreenType.equals(screenType);
    }

    /**
     * Точка расширения: вызывается после того, как все listener'ы уведомлены,
     * если хотя бы один из них бросил исключение. Поведение по умолчанию —
     * fail-fast (пробросить исключение дальше). Приложение может переопределить
     * этот метод в своей реализации {@link AbstractScreenNavigator}, чтобы вместо
     * падения залогировать ошибку.
     *
     * @param e первое пойманное исключение; последующие исключения от других
     *          listener'ов присоединены как {@link Throwable#getSuppressed()}
     */
    protected void handleListenerError(RuntimeException e) {
        throw e;
    }

    private V viewOf(Screen<?, ?, ?> screen) {
        return viewType.cast(screen.getView());
    }

    /**
     * Лёгкая, неблокирующая проверка: выполняется ли текущий код на UI-потоке тулкита
     * (EDT для Swing, FX Application Thread для JavaFX). В отличие от {@link #runOnUiThread},
     * ничего не планирует и не ждёт — просто отвечает на вопрос "прямо сейчас мы на UI-потоке?".
     * Используется в {@link #evict} для fail-fast до мутации состояния навигатора.
     */
    protected abstract boolean isUiThread();

    /**
     * Убирает {@code view} эвикнутого экрана из UI-дерева тулкита. Вызывается из {@link #evict}
     * сразу после того, как экран убран из внутреннего реестра навигатора, но до того как
     * {@link ScreenFactory#evict} вызовет {@link ScreenLifecycle#onDestroy()} — то есть view ещё
     * гарантированно валиден (его логика/presenter ещё не уничтожены) в момент удаления из дерева.
     */
    protected abstract void detach(Class<?> screenType, V view);

    /**
     * Первый показ экрана — добавить его view в UI-дерево.
     */
    protected abstract void attach(Class<?> screenType, V view);

    /**
     * Сделать {@code view} видимым (переключить экран).
     */
    protected abstract void display(Class<?> screenType, V view);

    /**
     * Создаёт (но не обязательно сразу показывает) модальное окно для {@code view}.
     */
    protected abstract ModalHandle createModal(Class<?> screenType, V view);

    /**
     * Выполняет {@code action} на UI-потоке тулкита (EDT для Swing, FX Application Thread для JavaFX).
     */
    protected abstract void runOnUiThread(Runnable action);
}