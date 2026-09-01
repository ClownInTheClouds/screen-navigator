package dev.sorokin.screennavigator;

import java.util.*;

/**
 * Toolkit-agnostic часть навигации: реестр показанных экранов, back-стек, диспетчеризация
 * лайфцикл-колбэков. Конкретный тулкит реализует только {@link #attach} / {@link #display} —
 * то, что реально трогает UI-дерево конкретного фреймворка.
 *
 * @param <V> тип view конкретного тулкита ({@code JComponent}, {@code Node}, ...)
 */
public abstract class AbstractScreenNavigator<V> implements ScreenNavigator {

    private final Map<Class<? extends Screen<?, ?, ?>>, Screen<?, ?, ?>> attached = new HashMap<>();
    private final Deque<Class<? extends Screen<?, ?, ?>>> history = new ArrayDeque<>();
    private final ScreenFactory screenFactory = new ScreenFactory();

    private Class<? extends Screen<?, ?, ?>> currentScreenType;
    private Screen<?, ?, ?> currentScreen;

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
        show(screenType, true);
    }

    @Override
    public boolean back() {
        if (history.isEmpty()) return false;
        var previousType = history.pop();
        show(previousType, false); // false — не пушим текущий экран обратно в историю
        return true;
    }

    @Override
    public Screen<?, ?, ?> getCurrentScreen() {
        return currentScreen;
    }

    @SuppressWarnings("unchecked")
    private <T extends Screen<?, ?, ?>> void show(Class<T> screenType, boolean pushHistory) {
        var next = screenFactory.get(screenType);
        if (!attached.containsKey(screenType)) {
            attached.put(screenType, next);
            attach(screenType, viewOf(next));
        }
        if (currentScreen != null) {
            currentScreen.onHide();
            if (pushHistory) {
                history.push(currentScreenType);
            }
        }
        display(screenType, viewOf(next));
        next.onShow();
        currentScreen = next;
        currentScreenType = (Class<? extends Screen<?, ?, ?>>) screenType;
    }

    @SuppressWarnings("unchecked")
    private V viewOf(Screen<?, ?, ?> screen) {
        return (V) screen.getView();
    }

    /** Первый показ экрана — добавить его view в UI-дерево. */
    protected abstract void attach(Class<?> screenType, V view);

    /** Сделать {@code view} видимым (переключить экран). */
    protected abstract void display(Class<?> screenType, V view);
}