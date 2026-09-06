package dev.sorokin.screennavigator.pattern;

/**
 * Базовый класс MVP-презентера: хранит ссылку на view и предоставляет точки
 * расширения для реакции на присоединение/отсоединение view — по аналогии
 * с хуками, доступными в {@link Controller}/{@link ViewModel} через
 * подписки на изменения модели.
 *
 * @param <PV> тип view, с которым работает презентер
 */
public abstract class Presenter<PV> {
    protected PV view;

    public void attachView(PV view) {
        this.view = view;
        onViewAttached();
    }

    public void detachView() {
        onViewDetached();
        this.view = null;
    }

    /**
     * Вызывается сразу после присвоения {@link #view} в {@link #attachView}.
     * Переопределите для инициализации, зависящей от view (например,
     * первичное обновление отображаемых данных).
     */
    protected void onViewAttached() { }

    /**
     * Вызывается перед обнулением {@link #view} в {@link #detachView} —
     * на момент вызова {@code view} ещё доступен. Переопределите для
     * освобождения ресурсов, специфичных для текущей view (аналог
     * {@link Screen#disposeOnDestroy(Runnable)}, но на уровне презентера).
     */
    protected void onViewDetached() { }
}