package dev.sorokin.screennavigator;

import java.util.*;
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

    private final Class<V> viewType;
    private final Map<Class<? extends Screen<?, ?, ?>>, Screen<?, ?, ?>> attached = new HashMap<>();
    private final Deque<Class<? extends Screen<?, ?, ?>>> history = new ArrayDeque<>();
    private final List<ScreenNavigatorListener> listeners = new CopyOnWriteArrayList<>();
    private final ScreenFactory screenFactory = new ScreenFactory();

    private Class<? extends Screen<?, ?, ?>> currentScreenType;
    private Screen<?, ?, ?> currentScreen;

    /**
     * @param viewType класс view конкретного тулкита, например {@code JComponent.class} у
     *                 Swing-реализации или {@code Parent.class} у JavaFX-реализации. Используется
     *                 только для {@link Class#cast(Object)} внутри {@link #viewOf}, чтобы не
     *                 писать unchecked-приведение типа.
     */
    protected AbstractScreenNavigator(Class<V> viewType) {
        this.viewType = viewType;
    }

    @Override
    public void install(SceneConfigurer sceneConfigurer, SceneConfigurer... additional) {
        sceneConfigurer.configure(screenFactory);
        if (additional == null) return;
        for (var config : additional) {
            config.configure(screenFactory);
        }
    }

    @Override
    public <T extends Screen<?, ?, ?>> void show(Class<T> screenType) {
        var screen = screenFactory.get(screenType);
        present(screenType, screen, true);
    }

    @Override
    public <SD, T extends Screen<?, ?, SD>> void show(Class<T> screenType, SD data) {
        var screen = screenFactory.get(screenType);
        // data доставляется внутри presentWithData(), на UI-потоке, перед onShow() — см. п. 2.6.
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
            var screen = screenFactory.get(screenType); // тяжёлое создание — не на UI-потоке
            // data не устанавливается здесь: захватывается лямбдой и доставляется внутри
            // presentWithData() на UI-потоке — конкурентные showAsync для одного screenType
            // больше не конкурируют за общее поле sceneData.
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

    @Override
    public void evict(Class<? extends Screen<?, ?, ?>> screenType) {
        requireUiThread();
        var screen = attached.remove(screenType);
        if (screen != null) {
            detach(screenType, viewOf(screen));
        }
        if (screenType.equals(currentScreenType)) {
            currentScreen = null;
            currentScreenType = null;
        }
        history.remove(screenType);
        screenFactory.evict(screenType);
        fire(l -> l.onScreenDestroyed(screenType));
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

    /** Показ без scene-данных (обычный {@code show(Class)}/{@code back()}). */
    private <T extends Screen<?, ?, ?>> void present(Class<T> screenType, T screen, boolean pushHistory) {
        presentInternal(screenType, screen, pushHistory, () -> { });
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

    /** Общий конвейер показа; {@code deliverSceneData} — no-op для варианта без данных. */
    private <T extends Screen<?, ?, ?>> void presentInternal(Class<T> screenType, T screen, boolean pushHistory, Runnable deliverSceneData) {
        requireUiThread();
        boolean firstShow = !attached.containsKey(screenType);
        if (firstShow) {
            attached.put(screenType, screen);
            attach(screenType, viewOf(screen));
            fire(l -> l.onScreenCreated(screenType));
        }
        if (currentScreen != null) {
            currentScreen.onHide();
            fire(l -> l.onScreenHidden(currentScreenType));
            if (pushHistory) {
                history.push(currentScreenType);
            }
        }
        display(screenType, viewOf(screen));
        deliverSceneData.run();
        screen.onShow();
        fire(l -> l.onScreenShown(screenType));
        currentScreen = screen;
        currentScreenType = screenType;
    }

    private <T extends Screen<?, ?, ?>> Runnable presentModal(Class<T> screenType, T screen) {
        return presentModalInternal(screenType, screen, () -> { });
    }

    /** Причина отдельного имени (вместо перегрузки) — та же, что и у {@link #presentWithData}. */
    private <SD, T extends Screen<?, ?, SD>> Runnable presentModalWithData(Class<T> screenType, T screen, SD data) {
        return presentModalInternal(screenType, screen, () -> deliverSceneData(screen, data));
    }

    private <T extends Screen<?, ?, ?>> Runnable presentModalInternal(Class<T> screenType, T screen, Runnable deliverSceneData) {
        requireUiThread();
        var handle = createModal(screenType, viewOf(screen));
        var closed = new AtomicBoolean(false);
        Runnable closeAction = () -> {
            if (closed.compareAndSet(false, true)) {
                handle.close();
            }
        };
        if (screen instanceof ModalScreen modalScreen) {
            modalScreen.bindCloseAction(closeAction);
        }
        fire(l -> l.onModalOpened(screenType));
        deliverSceneData.run();
        screen.onShow();
        handle.show();
        closeAction.run();
        screen.onHide();
        fire(l -> l.onModalClosed(screenType));
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

    private void fire(Consumer<ScreenNavigatorListener> event) {
        for (var listener : listeners) {
            event.accept(listener);
        }
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

    /** Первый показ экрана — добавить его view в UI-дерево. */
    protected abstract void attach(Class<?> screenType, V view);

    /** Сделать {@code view} видимым (переключить экран). */
    protected abstract void display(Class<?> screenType, V view);

    /** Создаёт (но не обязательно сразу показывает) модальное окно для {@code view}. */
    protected abstract ModalHandle createModal(Class<?> screenType, V view);

    /** Выполняет {@code action} на UI-потоке тулкита (EDT для Swing, FX Application Thread для JavaFX). */
    protected abstract void runOnUiThread(Runnable action);
}