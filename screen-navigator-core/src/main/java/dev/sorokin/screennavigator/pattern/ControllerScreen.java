package dev.sorokin.screennavigator.pattern;

import dev.sorokin.screennavigator.Screen;

/**
 * Опциональный convenience-базовый класс для экранов, чья бизнес-логика реализована
 * через {@link Controller}. Автоматически связывает {@link Controller#bindModel(Runnable)}
 * с лайфциклом экрана: подписка оформляется в {@link #onCreate()} и гарантированно
 * отписывается через {@link Screen#disposeOnDestroy(Runnable)} при {@code onDestroy()}.
 *
 * <p>Использование этого класса не обязательно — прямое использование {@link Controller}
 * без наследования от {@code ControllerScreen} остаётся полностью поддерживаемым
 * сценарием для случаев, когда автосвязка не нужна или нужна с нестандартной логикой.
 *
 * @param <V>  тип view экрана (toolkit-specific, например {@code JComponent}/{@code Parent})
 * @param <C>  тип контроллера, должен реализовывать {@link Controller}
 * @param <SD> тип scene data экрана
 */
public abstract class ControllerScreen<V, C extends Controller<?>, SD> extends Screen<V, C, SD> {

    protected ControllerScreen(V view, C logic) {
        super(view, logic);
    }

    @Override
    public void onCreate() {
        disposeOnDestroy(getLogic().bindModel(this::onModelChanged));
    }

    /**
     * Вызывается при любом изменении модели контроллера (после {@link #onCreate()}).
     * По умолчанию — no-op; переопределите для обновления view.
     */
    protected void onModelChanged() {

    }
}