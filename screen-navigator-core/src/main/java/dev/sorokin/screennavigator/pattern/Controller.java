package dev.sorokin.screennavigator.pattern;

/** MVC: контроллер напрямую владеет моделью и меняет её в ответ на события view. */
public interface Controller<M> {

    M getModel();

    /**
     * Подписывает {@code onModelChanged} на уведомления об изменении модели, если модель их
     * поддерживает. Реализация по умолчанию работает, когда {@code M} — {@link ViewModel}: любое
     * изменение любого свойства (см. {@link ViewModel#notifyListeners}) вызывает
     * {@code onModelChanged}, без учёта того, какое именно свойство изменилось.
     *
     * <p>Для контроллеров, чья модель не {@link ViewModel} (простой POJO без уведомлений),
     * реализация по умолчанию — no-op: {@code onModelChanged} никогда не будет вызван, и метод
     * не бросает исключение. Если такому контроллеру всё же нужна подписка — переопределите этот
     * метод, реализовав собственный механизм уведомлений на своей модели.
     *
     * @param onModelChanged вызывается при любом изменении модели (см. ограничение выше)
     * @return действие для отписки; безопасно вызывать более одного раза
     */
    default Runnable bindModel(Runnable onModelChanged) {
        var model = getModel();
        if (model instanceof ViewModel viewModel) {
            ViewModel.ChangeListener listener = (_, _, _) -> onModelChanged.run();
            viewModel.addListener(listener);
            return () -> viewModel.removeListener(listener);
        }
        return () -> { };
    }
}