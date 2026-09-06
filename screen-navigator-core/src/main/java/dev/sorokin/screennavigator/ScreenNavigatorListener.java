package dev.sorokin.screennavigator;

/**
 * Слушатель событий жизненного цикла экранов навигатора.
 * <p>
 * Все методы получают {@link Class} экрана исключительно как идентификатор
 * (для логирования, сравнения, построения аналитики) — тип намеренно не
 * привязан к границам {@code Screen<V, L, SD>}, чтобы не заставлять
 * реализующий код писать многословные wildcard-типы там, где реальное
 * использование generic-параметров экрана не требуется.
 */
public interface ScreenNavigatorListener {
    default void onScreenCreated(Class<?> screenType) {
    }

    default void onScreenShown(Class<?> screenType) {
    }

    default void onScreenHidden(Class<?> screenType) {
    }

    default void onScreenDestroyed(Class<?> screenType) {
    }

    default void onModalOpened(Class<?> screenType) {
    }

    default void onModalClosed(Class<?> screenType) {
    }
}