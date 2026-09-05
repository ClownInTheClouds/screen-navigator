package dev.sorokin.screennavigator.javafx;

import dev.sorokin.screennavigator.AbstractScreenNavigator;
import dev.sorokin.screennavigator.ModalHandle;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;

public final class JavaFxScreenNavigator extends AbstractScreenNavigator<Parent> {

    private final StackPane rootContainer;

    public JavaFxScreenNavigator(StackPane rootContainer) {
        super(Parent.class);
        this.rootContainer = rootContainer;
    }

    @Override
    protected void attach(Class<?> screenType, Parent view) {
        checkFxThread();
        view.setVisible(false);
        rootContainer.getChildren().add(view);
    }

    @Override
    protected void display(Class<?> screenType, Parent view) {
        checkFxThread();
        for (var node : rootContainer.getChildren()) {
            node.setVisible(node == view);
        }
    }

    @Override
    protected ModalHandle createModal(Class<?> screenType, Parent view) {
        checkFxThread();
        var stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(screenType.getSimpleName());
        stage.setScene(new Scene(view));
        return new ModalHandle() {
            @Override
            public void show() {
                stage.showAndWait(); // блокирует FX Application Thread до close()
            }

            @Override
            public void close() {
                stage.close();
            }
        };
    }

    @Override
    protected void runOnUiThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    @Override
    protected void detach(Class<?> screenType, Parent view) {
        checkFxThread();
        rootContainer.getChildren().remove(view);
    }

    @Override
    protected boolean isUiThread() {
        return Platform.isFxApplicationThread();
    }

    public StackPane getRootContainer() {
        return rootContainer;
    }

    private void checkFxThread() { // <-- убрали static: теперь вызывает instance-метод isUiThread()
        if (!isUiThread()) {
            throw new IllegalStateException(
                    "JavaFxScreenNavigator must be used from the FX Application Thread; "
                            + "wrap the call in Platform.runLater(...)");
        }
    }
}
