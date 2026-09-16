package io.nova.spring.data;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Registers Nova repository factory bean definitions for already-resolved base packages.
 * The annotation-driven and Spring Boot auto-configuration paths share this implementation
 * so bean naming, wiring, duplicate handling, and repository filtering cannot drift apart.
 */
public final class NovaRepositoryBeanDefinitionRegistrar {

    /** Infrastructure bean-definition name used to signal explicit repository configuration. */
    public static final String EXPLICIT_CONFIGURATION_MARKER =
            "io.nova.spring.data.EnableNovaRepositories.explicitConfiguration";

    private static final String REPOSITORY_INTERFACE_ATTRIBUTE =
            "io.nova.spring.data.repositoryInterface";

    /** Creates a repository registration helper. */
    public NovaRepositoryBeanDefinitionRegistrar() {
    }

    /**
     * Marks the registry so Spring Boot auto-configuration backs off completely.
     *
     * @param registry target bean-definition registry
     */
    public void markExplicitConfiguration(BeanDefinitionRegistry registry) {
        if (registry.containsBeanDefinition(EXPLICIT_CONFIGURATION_MARKER)) {
            return;
        }
        RootBeanDefinition marker = new RootBeanDefinition(Object.class);
        marker.setAbstract(true);
        marker.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        registry.registerBeanDefinition(EXPLICIT_CONFIGURATION_MARKER, marker);
    }

    /**
     * Checks whether an explicit {@link EnableNovaRepositories} declaration has been processed.
     *
     * @param registry target bean-definition registry
     * @return {@code true} when explicit repository configuration owns registration
     */
    public boolean hasExplicitConfiguration(BeanDefinitionRegistry registry) {
        return registry.containsBeanDefinition(EXPLICIT_CONFIGURATION_MARKER);
    }

    /**
     * Registers every concrete repository under the supplied packages.
     *
     * @param registry target bean-definition registry
     * @param basePackages packages to scan recursively
     * @param entityOperationsRef entity-operations bean name, or blank for the default
     * @param dialectRef optional dialect bean name
     * @param entityMetadataFactoryRef optional metadata-factory bean name
     * @param backOffOnExistingBeanName when {@code true}, an existing user bean with the
     *                                  conventional repository name wins; explicit registration
     *                                  preserves its existing replacement behavior
     */
    public void registerRepositories(
            BeanDefinitionRegistry registry,
            Iterable<String> basePackages,
            String entityOperationsRef,
            String dialectRef,
            String entityMetadataFactoryRef,
            boolean backOffOnExistingBeanName) {
        String operationsRef = StringUtils.hasText(entityOperationsRef)
                ? entityOperationsRef : "novaEntityOperations";
        RepositoryScanner scanner = new RepositoryScanner();
        ClassLoader classLoader = resolveClassLoader();

        for (String basePackage : basePackages) {
            if (!StringUtils.hasText(basePackage)) {
                continue;
            }
            for (BeanDefinition candidate : scanner.scan(basePackage)) {
                Class<?> repositoryInterface = loadRepositoryInterface(candidate, classLoader);
                if (repositoryInterface == null || !repositoryInterface.isInterface()
                        || ReactiveCrudRepository.class.equals(repositoryInterface)) {
                    continue;
                }
                String beanName = defaultBeanName(repositoryInterface);
                if (registry.containsBeanDefinition(beanName)) {
                    BeanDefinition existing = registry.getBeanDefinition(beanName);
                    Object registeredInterface = existing.getAttribute(REPOSITORY_INTERFACE_ATTRIBUTE);
                    if (repositoryInterface.getName().equals(registeredInterface)) {
                        continue;
                    }
                    if (backOffOnExistingBeanName) {
                        continue;
                    }
                }

                BeanDefinitionBuilder builder = BeanDefinitionBuilder
                        .genericBeanDefinition(NovaRepositoryFactoryBean.class)
                        .addConstructorArgValue(repositoryInterface)
                        .addPropertyValue("entityOperations", new RuntimeBeanReference(operationsRef));
                if (StringUtils.hasText(dialectRef)) {
                    builder.addPropertyValue("dialect", new RuntimeBeanReference(dialectRef));
                }
                if (StringUtils.hasText(entityMetadataFactoryRef)) {
                    builder.addPropertyValue("entityMetadataFactory",
                            new RuntimeBeanReference(entityMetadataFactoryRef));
                }
                BeanDefinition definition = builder.getBeanDefinition();
                definition.setAttribute(REPOSITORY_INTERFACE_ATTRIBUTE, repositoryInterface.getName());
                registry.registerBeanDefinition(beanName, definition);
            }
        }
    }

    private Class<?> loadRepositoryInterface(BeanDefinition candidate, ClassLoader classLoader) {
        String className = candidate.getBeanClassName();
        if (className == null) {
            return null;
        }
        try {
            return ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Could not load repository interface " + className, exception);
        }
    }

    private ClassLoader resolveClassLoader() {
        ClassLoader loader = NovaRepositoryBeanDefinitionRegistrar.class.getClassLoader();
        return loader != null ? loader : ClassUtils.getDefaultClassLoader();
    }

    private String defaultBeanName(Class<?> repositoryInterface) {
        String simpleName = repositoryInterface.getSimpleName();
        if (simpleName.isEmpty()) {
            return repositoryInterface.getName();
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
