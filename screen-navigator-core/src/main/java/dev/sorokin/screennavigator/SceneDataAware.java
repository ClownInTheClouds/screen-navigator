package dev.sorokin.screennavigator;

/**
 * Опциональный интерфейс для экранов, которым нужны scene-данные из {@code show(Class, SD)},
 * {@code showAsync(Class, SD, Executor)} или {@code showModal(Class, SD)}.
 *
 * <p>В отличие от {@link Screen#sceneData(Object)} — общего мутируемого поля экземпляра экрана,
 * закэшированного в {@link ScreenFactory} и переиспользуемого между показами — {@link #onShow(Object)}
 * получает данные конкретного вызова {@code show(...)} как параметр метода, вызываемого строго на
 * UI-потоке в момент реального показа экрана, внутри {@code AbstractScreenNavigator.present(...)}.
 * Это устраняет гонку, при которой конкурентные {@code showAsync}-вызовы для одного и того же типа
 * экрана на разных потоках пула конкурируют за одно и то же поле {@code sceneData} до перехода на
 * UI-поток.
 *
 * <p>Экран без scene data этот интерфейс не реализует. Экран, которому важна консистентность данных
 * при конкурентных вызовах {@code show}, должен реализовать этот интерфейс вместо использования
 * {@link Screen#sceneData(Object)}.
 *
 * @param <SD> тип scene-данных, см. {@link Screen}
 */
public interface SceneDataAware<SD> {

    /**
     * Вызывается вместо {@link Screen#sceneData(Object)}, непосредственно перед
     * {@link ScreenLifecycle#onShow()}, на UI-потоке, с данными конкретного вызова {@code show}.
     */
    void onShow(SD data);
}