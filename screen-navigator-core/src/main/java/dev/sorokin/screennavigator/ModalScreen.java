package dev.sorokin.screennavigator;

/**
 * Реализуется экранами, которые должны уметь закрыть себя сами (кнопки "Сохранить" / "Отмена" в
 * диалоге редактирования). Навигатор вызывает {@link #bindCloseAction} перед показом модального
 * окна, передавая действие, закрывающее это конкретное окно.
 */
public interface ModalScreen {

    void bindCloseAction(Runnable closeAction);
}
