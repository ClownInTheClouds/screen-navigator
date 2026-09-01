package dev.sorokin.screennavigator.swing;

import dev.sorokin.screennavigator.Screen;

import javax.swing.*;

public abstract class SwingScreen<L, SD> extends Screen<JComponent, L, SD> {
    protected SwingScreen(JComponent view, L logic) {
        super(view, logic);
    }

    protected SwingScreen(JComponent view, L logic, SD sceneData) {
        super(view, logic, sceneData);
    }
}