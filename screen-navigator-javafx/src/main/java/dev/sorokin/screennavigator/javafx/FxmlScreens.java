package dev.sorokin.screennavigator.javafx;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.io.IOException;
import java.net.URL;

public final class FxmlScreens {

    private FxmlScreens() {
    }

    public static <C> Loaded<C> load(URL fxml) {
        try {
            var loader = new FXMLLoader(fxml);
            Parent root = loader.load();
            C controller = loader.getController();
            return new Loaded<>(root, controller);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load FXML: " + fxml, e);
        }
    }

    public record Loaded<C>(Parent view, C controller) {
    }
}
