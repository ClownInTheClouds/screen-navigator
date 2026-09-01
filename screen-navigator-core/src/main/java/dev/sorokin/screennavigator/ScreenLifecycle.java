package dev.sorokin.screennavigator;

public interface ScreenLifecycle {

    /** Вызывается один раз, при первом обращении к экрану. */
    default void onCreate() {
    }

    /** Экран становится видимым. */
    default void onShow() {
    }

    /** Экран скрывается (переключение на другой экран). */
    default void onHide() {
    }

    /** Экран удаляется из кэша навигатора и больше не будет переиспользован. */
    default void onDestroy() {
    }
}