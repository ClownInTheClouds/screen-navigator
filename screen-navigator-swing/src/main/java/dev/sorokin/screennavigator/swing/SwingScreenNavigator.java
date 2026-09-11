package dev.sorokin.screennavigator.swing;

import dev.sorokin.screennavigator.AbstractScreenNavigator;
import dev.sorokin.screennavigator.ModalHandle;

import javax.swing.*;
import java.awt.*;

public final class SwingScreenNavigator extends AbstractScreenNavigator<JComponent> {

    private final Container rootContainer;

    public SwingScreenNavigator(Container rootContainer) {
        super(JComponent.class);
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

    /**
     * Создаёт модальный {@link JDialog} с {@code view} в качестве content pane.
     * <p>
     * Возвращённый {@link ModalHandle#show()} вызывает {@code dialog.setVisible(true)},
     * что блокирует EDT до {@link ModalHandle#close()} — обычное поведение модального
     * {@code JDialog} в Swing.
     */
    @Override
    protected ModalHandle createModal(Class<?> screenType, JComponent view) {
        checkEdt();
        var owner = SwingUtilities.getWindowAncestor(rootContainer);
        var dialog = new JDialog(owner, screenType.getSimpleName(), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setContentPane(view);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        return new ModalHandle() {
            @Override
            public void show() {
                dialog.setVisible(true);
            }

            @Override
            public void close() {
                dialog.dispose();
            }
        };
    }

    @Override
    protected void runOnUiThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    @Override
    protected void detach(Class<?> screenType, JComponent view) {
        checkEdt();
        rootContainer.remove(view);
        rootContainer.revalidate();
        rootContainer.repaint();
    }

    @Override
    protected boolean isUiThread() {
        return SwingUtilities.isEventDispatchThread();
    }

    public Container getRootContainer() {
        return rootContainer;
    }

    private void checkEdt() {
        if (!isUiThread()) {
            throw new IllegalStateException(
                    "SwingScreenNavigator must be used from the EDT; "
                            + "wrap the call in SwingUtilities.invokeLater(...)");
        }
    }
}
