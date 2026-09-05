// screen-navigator-core/src/main/java/dev/sorokin/screennavigator/AbstractScreenNavigator.java
package dev.sorokin.screennavigator;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
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
        screen.sceneData(data);
        present(screenType, screen, true);
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
            screen.sceneData(data);
            runOnUiThread(() -> present(screenType, screen, true));
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
        screen.sceneData(data);
        return presentModal(screenType, screen);
    }

    @Override
    public boolean back() {
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

    private <T extends Screen<?, ?, ?>> void present(Class<T> screenType, T screen, boolean pushHistory) {
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
        screen.onShow();
        fire(l -> l.onScreenShown(screenType));
        currentScreen = screen;
        currentScreenType = screenType;
    }

    private <T extends Screen<?, ?, ?>> Runnable presentModal(Class<T> screenType, T screen) {
        var handle = createModal(screenType, viewOf(screen));
        if (screen instanceof ModalScreen modalScreen) {
            modalScreen.bindCloseAction(handle::close);
        }
        fire(l -> l.onModalOpened(screenType));
        screen.onShow();
        handle.show(); // для Swing/JavaFX-модалок блокирует вызывающий поток до close()
        return () -> {
            screen.onHide();
            fire(l -> l.onModalClosed(screenType));
            handle.close();
        };
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
