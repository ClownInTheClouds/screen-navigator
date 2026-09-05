package dev.sorokin.screennavigator;

import java.util.concurrent.Executor;

public interface ScreenNavigator {

    void install(SceneConfigurer sceneConfigurer, SceneConfigurer... additional);

    <T extends Screen<?, ?, ?>> void show(Class<T> screenType);

    /** Показывает экран, предварительно передав ему {@code data} через {@link Screen#sceneData}. */
    <SD, T extends Screen<?, ?, SD>> void show(Class<T> screenType, SD data);

    /** Создаёт экран на {@code backgroundExecutor}, показывает на UI-потоке, когда готово. */
    <T extends Screen<?, ?, ?>> void showAsync(Class<T> screenType, Executor backgroundExecutor);

    <SD, T extends Screen<?, ?, SD>> void showAsync(Class<T> screenType, SD data, Executor backgroundExecutor);

    /**
     * @return действие для принудительного закрытия модального окна извне текущего потока
     *         (например, из таймаут-потока через {@code runOnUiThread(closeAction)}).
     *         В штатном сценарии — когда пользователь закрывает диалог кнопкой или системным
     *         крестиком — вызывать этот {@code Runnable} не требуется: {@code showModal} уже
     *         вернёт управление только после того, как {@link ScreenLifecycle#onHide()} и
     *         {@link ScreenNavigatorListener#onModalClosed} гарантированно отработали.
     */
    <T extends Screen<?, ?, ?>> Runnable showModal(Class<T> screenType);

    <SD, T extends Screen<?, ?, SD>> Runnable showModal(Class<T> screenType, SD data);

    /** @return {@code true}, если в истории был предыдущий экран и переход выполнен */
    boolean back();

    Screen<?, ?, ?> getCurrentScreen();

    /** Убирает экран из кэша навигатора; при повторном {@link #show} будет создан заново. */
    void evict(Class<? extends Screen<?, ?, ?>> screenType);

    void addListener(ScreenNavigatorListener listener);

    void removeListener(ScreenNavigatorListener listener);
}
