package dev.sorokin.screennavigator;

/**
 * @param <V>  тип view; в core — произвольный (Object), в screen-navigator-swing
 *             сужается до {@code JComponent}, в screen-navigator-javafx — до {@code Node}
 * @param <L>  тип "логики" экрана — Controller (MVC), Presenter (MVP) или ViewModel (MVVM)
 * @param <SD> тип данных, передаваемых экрану при навигации (scene data)
 */
public abstract class Screen<V, L, SD> implements ScreenLifecycle {

    protected final V view;
    protected final L logic;
    protected SD sceneData;

    protected Screen(V view, L logic) {
        this.view = view;
        this.logic = logic;
    }

    protected Screen(V view, L logic, SD sceneData) {
        this(view, logic);
        this.sceneData = sceneData;
    }

    public V getView() {
        return view;
    }

    public L getLogic() {
        return logic;
    }

    /**
     * @return последние установленные scene-данные. При использовании {@link SceneDataAware}
     *         это поле не обновляется — читайте данные из параметра {@link SceneDataAware#onShow}.
     */
    public SD getSceneData() {
        return sceneData;
    }

    /**
     * Fallback-механизм передачи scene-данных: общее мутируемое поле экземпляра, кэшированного в
     * {@code ScreenFactory}. Для экранов, участвующих в конкурентных сценариях показа
     * ({@code showAsync}), предпочтительнее реализовать {@link SceneDataAware} — тогда данные
     * приходят как параметр конкретного вызова показа, а не через это поле.
     */
    public void sceneData(SD sceneData) {
        this.sceneData = sceneData;
    }
}