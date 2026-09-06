package dev.sorokin.screennavigator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Базовый класс экрана: связывает toolkit-специфичный {@code view} (например,
 * {@code JComponent} или {@code Parent}) с произвольной бизнес-логикой {@code logic}
 * (контроллер, presenter, view-model — на усмотрение приложения) и опциональными
 * данными сцены {@code SD}.
 *
 * @param <V>  тип view конкретного UI-тулкита
 * @param <L>  тип логики экрана (например, {@link dev.sorokin.screennavigator.pattern.Controller})
 * @param <SD> тип данных, передаваемых при показе экрана через {@code show(Class, SD)}
 */
public abstract class Screen<V, L, SD> implements ScreenLifecycle {

    protected final V view;
    protected final L logic;
    protected SD sceneData;
    private final List<Runnable> disposables = new CopyOnWriteArrayList<>();

    protected Screen(V view, L logic) { this.view = view; this.logic = logic; }
    protected Screen(V view, L logic, SD sceneData) { this(view, logic); this.sceneData = sceneData; }

    /**
     * Регистрирует действие отписки (например, результат
     * {@link dev.sorokin.screennavigator.pattern.Controller#bindModel}), которое
     * будет выполнено автоматически при {@link #onDestroy()}.
     * <p>
     * Это основной механизм библиотеки для предотвращения утечек памяти через
     * подписки на долгоживущие объекты (например, {@link dev.sorokin.screennavigator.pattern.ViewModel}
     * уровня приложения): разработчику экрана не нужно вручную дублировать вызов
     * отписки в переопределённом {@code onDestroy()}.
     *
     * @param unsubscribe действие отписки; должно быть безопасно для повторного
     *                    вызова, если это не гарантировано вызывающим кодом
     */
    protected final void disposeOnDestroy(Runnable unsubscribe) {
        disposables.add(unsubscribe);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Базовая реализация выполняет все действия, зарегистрированные через
     * {@link #disposeOnDestroy(Runnable)}, и очищает реестр.
     * <p>
     * <b>Важно:</b> если наследник переопределяет {@code onDestroy()}, он обязан
     * вызвать {@code super.onDestroy()} — иначе зарегистрированные disposables
     * не будут выполнены и гарантия отсутствия утечек памяти будет нарушена.
     */
    @Override
    public void onDestroy() {
        for (var disposable : disposables) {
            disposable.run();
        }
        disposables.clear();
    }

    public V getView() { return view; }
    public L getLogic() { return logic; }
    public SD getSceneData() { return sceneData; }
    public void sceneData(SD sceneData) { this.sceneData = sceneData; }
}