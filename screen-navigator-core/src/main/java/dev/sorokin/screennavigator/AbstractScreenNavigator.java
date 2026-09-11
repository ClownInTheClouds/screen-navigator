package dev.sorokin.screennavigator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /**
     * Типы экранов, чьё модальное окно сейчас открыто (между {@code onModalOpened} и
     * {@code onModalClosed}). Используется как guard от повторного {@code showModal} для
     * того же типа, пока первое окно ещё не закрыто (см. {@link #presentModalInternal}),
     * и как источник данных для {@link #isModalOpen(Class)}/{@link #getOpenModalScreens()}.
     */
    private final Set<Class<? extends Screen<?, ?, ?>>> openModals = ConcurrentHashMap.newKeySet();
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
    public <T extends Screen<?, ?, ?>> void replace(Class<T> screenType) {
        requireUiThread();
        var screen = screenFactory.get(screenType);
        present(screenType, screen, false);
    }

    @Override
    public <SD, T extends Screen<?, ?, SD>> void replace(Class<T> screenType, SD data) {
        requireUiThread();
        var screen = screenFactory.get(screenType);
        presentWithData(screenType, screen, data, false);
    }

    @Override
    public <T extends Screen<?, ?, ?>> void show(Class<T> screenType) {
        requireUiThread();
        var screen = screenFactory.get(screenType);
        present(screenType, screen, true);
    }

    @Override
    public <SD, T extends Screen<?, ?, SD>> void show(Class<T> screenType, SD data) {
        requireUiThread();
        var screen = screenFactory.get(screenType);
        presentWithData(screenType, screen, data, true);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Создание экрана разделено на две фазы (см. {@link ScreenFactory#registerAsync}):
     * фоновая подготовка данных выполняется на {@code backgroundExecutor}, а собственно
     * конструирование {@link Screen} (и, как следствие, его toolkit-специфичного view)
     * всегда происходит на UI-потоке внутри {@link #runOnUiThread}. Это гарантирует, что
     * toolkit-компоненты никогда не создаются вне EDT/FX Application Thread, даже если
     * экран зарегистрирован через обычный {@link ScreenFactory#register} (в этом случае
     * фоновая фаза — тривиальный no-op, а вся сборка просто переносится на UI-поток
     * целиком, что уже безопасно).
     */
    @Override
    public <T extends Screen<?, ?, ?>> void showAsync(Class<T> screenType, Executor backgroundExecutor) {
        backgroundExecutor.execute(() -> {
            var prepared = screenFactory.prepareAsync(screenType);
            runOnUiThread(() -> {
                var screen = screenFactory.buildOnUiThread(screenType, prepared);
                present(screenType, screen, true);
            });
        });
    }

    /** @see #showAsync(Class, Executor) */
    @Override
    public <SD, T extends Screen<?, ?, SD>> void showAsync(Class<T> screenType, SD data, Executor backgroundExecutor) {
        backgroundExecutor.execute(() -> {
            var prepared = screenFactory.prepareAsync(screenType);
            runOnUiThread(() -> {
                var screen = screenFactory.buildOnUiThread(screenType, prepared);
                presentWithData(screenType, screen, data, true);
            });
        });
    }

    @Override
    public <T extends Screen<?, ?, ?>> Runnable showModal(Class<T> screenType) {
        requireUiThread();
        var screen = screenFactory.get(screenType);
        return presentModal(screenType, screen);
    }

    @Override
    public <SD, T extends Screen<?, ?, SD>> Runnable showModal(Class<T> screenType, SD data) {
        requireUiThread();
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

    /**
     * Проверяет, есть ли в истории навигации экран, к которому можно вернуться
     * вызовом {@link #back()}, без выполнения самого перехода.
     */
    @Override
    public boolean canGoBack() {
        requireUiThread();
        return !history.isEmpty();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Как и {@link #canGoBack()}/{@link #isShowing(Class)}, требует вызова с UI-потока:
     * {@code currentScreen} — обычное (не {@code volatile}) поле, мутируемое только на
     * UI-потоке, и вся модель конкурентности библиотеки построена на confinement к этому
     * потоку, а не на блокировках.
     *
     * @throws IllegalStateException если вызвано не из UI-потока
     */
    @Override
    public Screen<?, ?, ?> getCurrentScreen() {
        requireUiThread();
        return currentScreen;
    }

    /**
     * Проверяет, является ли экран указанного типа текущим показанным
     * (эквивалент {@code screenType.equals(currentScreenType)}, но без
     * необходимости обращаться к {@link #getCurrentScreen()} и делать
     * {@code instanceof}/{@code getClass().equals(...)} в коде приложения).
     */
    @Override
    public boolean isShowing(Class<?> screenType) {
        requireUiThread();
        return currentScreenType != null && currentScreenType.equals(screenType);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Не учитывает обычные (не модальные) экраны — см. {@link #getCurrentScreen()} для них.
     * Модальный флоу — намеренно отдельная от основной навигации плоскость.
     */
    @Override
    public boolean isModalOpen(Class<?> screenType) {
        requireUiThread();
        return openModals.contains(screenType);
    }

    @Override
    public Set<Class<? extends Screen<?, ?, ?>>> getOpenModalScreens() {
        requireUiThread();
        return Set.copyOf(openModals);
    }

    @Override
    public void clearHistory() {
        requireUiThread();
        history.clear();
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
        removeFromHistory(screenType);
        modalOnly.remove(screenType);
        openModals.remove(screenType);
        screenFactory.evict(screenType);
        fire(listener -> listener.onScreenDestroyed(screenType));
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

    /**
     * Лёгкая, неблокирующая проверка: выполняется ли текущий код на UI-потоке тулкита
     * (EDT для Swing, FX Application Thread для JavaFX). В отличие от {@link #runOnUiThread},
     * ничего не планирует и не ждёт — просто отвечает на вопрос "прямо сейчас мы на UI-потоке?".
     * Используется в {@link #evict} для fail-fast до мутации состояния навигатора.
     */
    protected abstract boolean isUiThread();

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
     * Убирает {@code view} эвикнутого экрана из UI-дерева тулкита. Вызывается из {@link #evict}
     * сразу после того, как экран убран из внутреннего реестра навигатора, но до того как
     * {@link ScreenFactory#evict} вызовет {@link ScreenLifecycle#onDestroy()} — то есть view ещё
     * гарантированно валиден (его логика/presenter ещё не уничтожены) в момент удаления из дерева.
     */
    protected abstract void detach(Class<?> screenType, V view);

    /**
     * Выполняет {@code action} на UI-потоке тулкита (EDT для Swing, FX Application Thread для JavaFX).
     */
    protected abstract void runOnUiThread(Runnable action);

    private void requireUiThread() {
        if (!isUiThread()) {
            throw new IllegalStateException(
                    "ScreenNavigator must be used from the UI thread; "
                            + "wrap the call in the toolkit's UI dispatch mechanism (SwingUtilities.invokeLater / Platform.runLater)");
        }
    }

    private V viewOf(Screen<?, ?, ?> screen) {
        return viewType.cast(screen.getView());
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
     * приводит к ambiguous method call при передаче лямбды. Разные имена методов убирают эту
     * категорию ошибок полностью.
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

    /**
     * Общий конвейер модального показа; {@code deliverSceneData} — no-op для варианта без данных.
     * <p>
     * Отклоняет повторный {@code showModal} для того же типа, пока первое модальное окно ещё
     * открыто: Swing/JavaFX прокачивают вложенный event loop внутри модального показа, поэтому
     * пользовательский код технически может вызвать {@code showModal(sameType)} второй раз до
     * закрытия первого окна — без этой проверки второй вызов попытался бы поместить тот же view
     * в новый контейнер, "телепортировав" его из первого окна.
     * <p>
     * {@code onModalOpened} фиксируется только после успешного {@code onShow()} — тогда парный
     * {@code onModalClosed} в {@code finally} гарантированно доставляется при любом исходе
     * (включая исключение в {@code deliverSceneData}/{@code onShow}/{@code handle.show()}), без
     * "утечки" состояния у слушателей, полагающихся на парность этих двух событий.
     */
    private <T extends Screen<?, ?, ?>> Runnable presentModalInternal(Class<T> screenType, T screen, Runnable deliverSceneData) {
        requireUiThread();
        if (attached.containsKey(screenType)) {
            throw new IllegalStateException(
                    screenType.getName() + " is already attached via show(...); "
                            + "a Screen instance must not be shown via both show(...) and showModal(...)");
        }
        if (!openModals.add(screenType)) {
            throw new IllegalStateException(
                    screenType.getName() + " is already open as a modal screen; "
                            + "close it before calling showModal(...) again for the same type");
        }
        modalOnly.add(screenType);

        var handle = createModal(screenType, viewOf(screen));
        var closed = new AtomicBoolean(false);
        Runnable closeAction = () -> {
            if (closed.compareAndSet(false, true)) handle.close();
        };
        if (screen instanceof ModalScreen modalScreen) modalScreen.bindCloseAction(closeAction);

        boolean shown = false;
        try {
            deliverSceneData.run();
            screen.onShow();
            shown = true;
            fire(listener -> listener.onModalOpened(screenType));
            handle.show();
        } finally {
            openModals.remove(screenType);
            closeAction.run();
            if (shown) {
                screen.onHide();
                fire(listener -> listener.onModalClosed(screenType));
            }
        }
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
     * Удаляет {@code screenType} из back-стека истории.
     * <p>
     * Используется {@link Deque#removeIf}, а не {@code remove(Object)}: один и тот же
     * {@code screenType} может встретиться в back-стеке несколько раз (последовательность
     * {@code show(A) -> show(B) -> show(A) -> show(C)} даёт историю {@code [A, B, A]}), а
     * {@code Deque.remove(Object)} убирает только первое вхождение — эвикнутый тип мог бы
     * "воскреснуть" через {@link #back()}.
     */
    private void removeFromHistory(Class<? extends Screen<?, ?, ?>> screenType) {
        history.removeIf(type -> type.equals(screenType));
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
}
