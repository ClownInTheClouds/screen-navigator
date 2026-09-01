package dev.sorokin.screennavigator.pattern;

/**
 * MVP: presenter получает "тупой" интерфейс view (без ссылки на реальный UI-класс) и
 * императивно вызывает его методы, вместо того чтобы view сам подписывался на модель.
 */
public abstract class Presenter<PV> {

    protected PV view;

    public void attachView(PV view) {
        this.view = view;
    }

    public void detachView() {
        this.view = null;
    }
}