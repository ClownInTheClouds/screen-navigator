package dev.sorokin.screennavigator;

/** Слушатель событий навигации — точка расширения для логирования/аналитики. */
public interface ScreenNavigatorListener {

    default void onScreenCreated(Class<? extends Screen<?, ?, ?>> screenType) {
    }

    default void onScreenShown(Class<? extends Screen<?, ?, ?>> screenType) {
    }

    default void onScreenHidden(Class<? extends Screen<?, ?, ?>> screenType) {
    }

    default void onScreenDestroyed(Class<? extends Screen<?, ?, ?>> screenType) {
    }

    default void onModalOpened(Class<? extends Screen<?, ?, ?>> screenType) {
    }

    default void onModalClosed(Class<? extends Screen<?, ?, ?>> screenType) {
    }
}
