package dev.sorokin.screennavigator;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class ScreenNavigator {

    private final Map<Class<? extends Screen<?, ?, ?>>, Screen<?, ?, ?>> screens = new HashMap<>();
    private final ScreenFactory screenFactory = new ScreenFactory();

    private final Container rootContainer;

    private Screen<?, ?, ?> currentScreen;

    public ScreenNavigator(Container rootContainer) {
        this.rootContainer = rootContainer;
        rootContainer.setLayout(new CardLayout());
    }

    public void install(SceneConfigurer sceneConfigurer, SceneConfigurer... additional) {
        sceneConfigurer.configure(screenFactory);
        if (additional == null) return;
        for (var additionalConfig : additional) {
            additionalConfig.configure(screenFactory);
        }
    }

    public <T extends Screen<?, ?, ?>> void show(Class<T> screenType) {
        var nextScreen = screenFactory.get(screenType);
        if (!screens.containsKey(screenType)) {
            screens.put(screenType, nextScreen);
            rootContainer.add(nextScreen.getView(), screenType.getName());
        }
        if (currentScreen != null) currentScreen.onHide();
        getCardLayout().show(rootContainer, screenType.getName());
        nextScreen.onShow();
        currentScreen = nextScreen;
    }

    private CardLayout getCardLayout() {
        return (CardLayout) rootContainer.getLayout();
    }

    public Container getRootContainer() {
        return rootContainer;
    }

    public Screen<?, ?, ?> getCurrentScreen() {
        return currentScreen;
    }
}
