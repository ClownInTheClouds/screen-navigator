package dev.sorokin.screennavigator;

public interface ScreenNavigator {

    void install(SceneConfigurer sceneConfigurer, SceneConfigurer... additional);

    <T extends Screen<?, ?, ?>> void show(Class<T> screenType);

    /** @return {@code true}, если в истории был предыдущий экран и переход выполнен */
    boolean back();

    Screen<?, ?, ?> getCurrentScreen();
}