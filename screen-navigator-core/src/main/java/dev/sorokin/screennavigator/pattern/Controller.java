package dev.sorokin.screennavigator.pattern;

/** MVC: контроллер напрямую владеет моделью и меняет её в ответ на события view. */
public interface Controller<M> {
    M getModel();
}