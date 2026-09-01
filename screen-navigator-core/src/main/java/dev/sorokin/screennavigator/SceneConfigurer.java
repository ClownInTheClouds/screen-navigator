package dev.sorokin.screennavigator;

/** Аналог {@code Module} из service-locator, но для регистрации фабрик экранов. */
public interface SceneConfigurer {
    void configure(ScreenFactory screenFactory);
}