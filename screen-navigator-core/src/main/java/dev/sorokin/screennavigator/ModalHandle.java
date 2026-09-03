package dev.sorokin.screennavigator;

/**
 * Хендл открытого модального окна, реализуемый конкретным тулкитом (Swing/JavaFX).
 * {@link #show()} отображает окно — для блокирующей модальности (Swing {@code JDialog},
 * JavaFX {@code Stage.showAndWait()}) вызов блокирует поток до {@link #close()}.
 * {@link #close()} закрывает окно программно.
 */
public interface ModalHandle {

    void show();

    void close();
}
