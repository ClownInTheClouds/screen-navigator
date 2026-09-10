package dev.sorokin.screennavigator;

import java.util.Set;
import java.util.concurrent.Executor;

public interface ScreenNavigator {

    /**
     * Регистрирует одну или несколько фабрик экранов через переданные
     * {@link SceneConfigurer}. Порядок вызова {@code configure(...)} соответствует
     * порядку аргументов.
     *
     * @param configurers конфигураторы сцены; пустой массив — легитимный no-op
     *                     (например, если приложение динамически решает,
     *                     что регистрировать нечего)
     */
    void install(SceneConfigurer... configurers);

    <T extends Screen<?, ?, ?>> void show(Class<T> screenType);

    /**
     * Показывает экран, передав ему {@code data}. Если {@code T} реализует {@link SceneDataAware},
     * данные доставляются через {@link SceneDataAware#onShow(Object)} как параметр конкретного вызова
     * показа (безопасно при конкурентных {@code show}/{@code showAsync} для одного {@code screenType}).
     * Иначе — fallback на {@link Screen#sceneData(Object)}.
     */
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

    /**
     * @return текущий показанный обычный (не модальный) экран. Открытые модальные окна
     *         не влияют на возвращаемое значение — модальный флоу является отдельной от
     *         основной навигации плоскостью; см. {@link #isModalOpen(Class)} для запроса
     *         состояния модальных окон.
     */
    Screen<?, ?, ?> getCurrentScreen();

    /**
     * @return {@code true}, если экран указанного типа сейчас открыт как модальное окно
     *         (между {@code onModalOpened} и {@code onModalClosed})
     */
    boolean isModalOpen(Class<?> screenType);

    /** @return read-only снимок типов экранов, чьи модальные окна сейчас открыты */
    Set<Class<? extends Screen<?, ?, ?>>> getOpenModalScreens();

    /** Убирает экран из кэша навигатора; при повторном {@link #show} будет создан заново. */
    void evict(Class<? extends Screen<?, ?, ?>> screenType);

    void addListener(ScreenNavigatorListener listener);

    void removeListener(ScreenNavigatorListener listener);

    /**
     * @return {@code true}, если в истории навигации есть экран,
     *         к которому можно вернуться через {@link #back()}
     */
    boolean canGoBack();

    /**
     * @return {@code true}, если экран указанного типа сейчас является
     *         текущим показанным (верхним) экраном навигатора
     */
    boolean isShowing(Class<?> screenType);

    /**
     * Показывает экран указанного типа, ЗАМЕНЯЯ текущий экран без добавления его
     * в историю навигации — {@link #back()} не сможет вернуться к экрану,
     * показанному до этого вызова. Полезно для splash → main, login → main после
     * logout и других сценариев, где предыдущий экран не должен быть достижим назад.
     */
    <T extends Screen<?, ?, ?>> void replace(Class<T> screenType);

    /** @see #replace(Class) — вариант с передачей {@code SceneData} */
    <SD, T extends Screen<?, ?, SD>> void replace(Class<T> screenType, SD data);

    /**
     * Очищает историю навигации: после вызова {@link #canGoBack()} вернёт {@code false},
     * а {@link #back()} не выполнит переход. Экраны, ранее находившиеся в истории,
     * при этом НЕ уничтожаются (в отличие от {@link #evict}) — их инстансы остаются
     * закэшированными в {@code ScreenFactory} и будут переиспользованы при повторном
     * {@link #show(Class)}.
     */
    void clearHistory();
}
