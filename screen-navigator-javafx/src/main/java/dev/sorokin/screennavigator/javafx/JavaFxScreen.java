package dev.sorokin.screennavigator.javafx;

import dev.sorokin.screennavigator.Screen;
import javafx.scene.Parent;

public abstract class JavaFxScreen<L, SD> extends Screen<Parent, L, SD> {
    protected JavaFxScreen(Parent view, L logic) {
        super(view, logic);
    }

    protected JavaFxScreen(Parent view, L logic, SD sceneData) {
        super(view, logic, sceneData);
    }
}
