package dev.sorokin.screennavigator.servicelocator;

import dev.sorokin.screennavigator.SceneConfigurer;
import dev.sorokin.screennavigator.Screen;
import dev.sorokin.screennavigator.ScreenFactory;
import dev.sorokin.servicelocator.ServiceRegistry;

import java.util.List;

/**
 * Регистрирует экраны в {@link ScreenFactory}, делегируя их создание {@link ServiceRegistry}.
 * Экраны при этом можно регистрировать в самом {@code ServiceRegistry} как обычно —
 * в том числе рефлективно, через {@code ReflectiveServiceRegistry.addFactory(ScreenType.class)},
 * если подключён service-locator-reflection.
 */
public final class ServiceLocatorSceneConfigurer implements SceneConfigurer {

    private final ServiceRegistry serviceRegistry;
    private final List<Class<? extends Screen<?, ?, ?>>> screenTypes;

    public ServiceLocatorSceneConfigurer(ServiceRegistry serviceRegistry,
                                         List<Class<? extends Screen<?, ?, ?>>> screenTypes) {
        this.serviceRegistry = serviceRegistry;
        this.screenTypes = List.copyOf(screenTypes);
    }

    @Override
    public void configure(ScreenFactory screenFactory) {
        for (var screenType : screenTypes) {
            screenFactory.register(screenType, () -> serviceRegistry.getService(screenType));
        }
    }
}