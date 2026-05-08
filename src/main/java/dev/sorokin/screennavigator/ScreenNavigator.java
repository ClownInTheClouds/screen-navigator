package dev.sorokin.screennavigator;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class ScreenNavigator {

    private final Map<Class<? extends Screen>, Screen> screens = new HashMap<>();
    private final ScreenFactory screenFactory = new ScreenFactory();
    private final JPanel rootPanel = new JPanel(new CardLayout());

    private Screen currentScreen;

    public void install(Configuration configuration, Configuration... additional) {
        configuration.configure(screenFactory);
        if (additional == null) return;
        for (var additionalConfig : additional) {
            additionalConfig.configure(screenFactory);
        }
    }

    public <T extends Screen> void show(Class<T> screenType) {
        var nextScreen = screenFactory.get(screenType);
        if (!screens.containsKey(screenType)) {
            screens.put(screenType, nextScreen);
            rootPanel.add(nextScreen, screenType.getName());
        }
        if (currentScreen != null) currentScreen.onHide();
        this.getLayout().show(rootPanel, screenType.getName());
        nextScreen.onShow();
        currentScreen = nextScreen;
    }

    private CardLayout getLayout() {
        return (CardLayout) rootPanel.getLayout();
    }

    public JPanel getRootPanel() {
        return rootPanel;
    }

    public Screen getCurrentScreen() {
        return currentScreen;
    }
}
