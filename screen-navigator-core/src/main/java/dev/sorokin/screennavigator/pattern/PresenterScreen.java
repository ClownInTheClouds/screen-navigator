package dev.sorokin.screennavigator.pattern;

import dev.sorokin.screennavigator.Screen;
import dev.sorokin.screennavigator.ScreenLifecycle;

/**
 * Опциональный convenience-базовый класс для экранов на MVP: автоматически вызывает
 * {@link Presenter#attachView(Object)} в {@link ScreenLifecycle#onShow()} и
 * {@link Presenter#detachView()} в {@link ScreenLifecycle#onHide()}, снимая необходимость
 * делать это вручную в каждом экране.
 *
 * @param <V>  тип view экрана, одновременно являющийся типом view для {@link Presenter}
 * @param <P>  тип presenter'а
 * @param <SD> тип scene data экрана
 */
public abstract class PresenterScreen<V, P extends Presenter<V>, SD> extends Screen<V, P, SD> {

    protected PresenterScreen(V view, P logic) {
        super(view, logic);
    }

    @Override
    public void onShow() {
        getLogic().attachView(getView());
    }

    @Override
    public void onHide() {
        getLogic().detachView();
    }
}