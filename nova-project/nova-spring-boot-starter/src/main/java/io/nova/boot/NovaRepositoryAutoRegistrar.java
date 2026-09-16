package io.nova.boot;

import io.nova.spring.data.NovaRepositoryBeanDefinitionRegistrar;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

/** Registers repository factory beans after Boot has established its application packages. */
final class NovaRepositoryAutoRegistrar
        implements BeanDefinitionRegistryPostProcessor, BeanFactoryAware, PriorityOrdered {

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        NovaRepositoryBeanDefinitionRegistrar registrar = new NovaRepositoryBeanDefinitionRegistrar();
        if (registrar.hasExplicitConfiguration(registry)
                || beanFactory == null
                || !AutoConfigurationPackages.has(beanFactory)) {
            return;
        }
        registrar.registerRepositories(
                registry,
                AutoConfigurationPackages.get(beanFactory),
                "novaEntityOperations",
                "",
                "",
                true);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // No bean mutation is required after repository definitions have been registered.
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
