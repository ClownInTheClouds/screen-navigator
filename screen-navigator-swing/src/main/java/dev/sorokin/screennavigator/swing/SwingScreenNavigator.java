package dev.sorokin.screennavigator.swing;

import dev.sorokin.screennavigator.AbstractScreenNavigator;

import javax.swing.*;
import java.awt.*;

public final class SwingScreenNavigator extends AbstractScreenNavigator<JComponent> {

    private final Container rootContainer;

    public SwingScreenNavigator(Container rootContainer) {
        this.rootContainer = rootContainer;
        rootContainer.setLayout(new CardLayout());
    }

    @Override
    protected void attach(Class<?> screenType, JComponent view) {
        checkEdt();
        rootContainer.add(view, screenType.getName());
    }

    @Override
    protected void display(Class<?> screenType, JComponent view) {
        checkEdt();
        ((CardLayout) rootContainer.getLayout()).show(rootContainer, screenType.getName());
    }

    public Container getRootContainer() {
        return rootContainer;
    }

    private static void checkEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "SwingScreenNavigator must be used from the EDT; "
                            + "wrap the call in SwingUtilities.invokeLater(...)");
        }
    }
}