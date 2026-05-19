package io.nova.spring.data;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link EnableNovaRepositories} 메타데이터에서 base 패키지를 추출하고, 각 base 패키지를
 * {@link RepositoryScanner}로 훑어 발견되는 모든 repository 인터페이스마다 한 개의
 * {@link NovaRepositoryFactoryBean} 빈 정의를 등록한다.
 */
public final class NovaRepositoriesRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableNovaRepositories.class.getName()));
        if (attributes == null) {
            throw new IllegalStateException(
                    "@EnableNovaRepositories metadata missing on " + importingClassMetadata.getClassName());
        }
        Set<String> basePackages = resolveBasePackages(importingClassMetadata, attributes);
        String entityOperationsRef = attributes.getString("entityOperationsRef");
        if (!StringUtils.hasText(entityOperationsRef)) {
            entityOperationsRef = "novaEntityOperations";
        }

        RepositoryScanner scanner = new RepositoryScanner();
        ClassLoader classLoader = resolveClassLoader(importingClassMetadata);

        for (String basePackage : basePackages) {
            Set<BeanDefinition> candidates = scanner.scan(basePackage);
            for (BeanDefinition candidate : candidates) {
                String className = candidate.getBeanClassName();
                if (className == null) {
                    continue;
                }
                Class<?> repositoryInterface;
                try {
                    repositoryInterface = ClassUtils.forName(className, classLoader);
                } catch (ClassNotFoundException exception) {
                    throw new IllegalStateException(
                            "Could not load repository interface " + className, exception);
                }
                if (!repositoryInterface.isInterface()) {
                    continue;
                }
                if (ReactiveCrudRepository.class.equals(repositoryInterface)) {
                    continue;
                }
                BeanDefinitionBuilder builder = BeanDefinitionBuilder
                        .genericBeanDefinition(NovaRepositoryFactoryBean.class)
                        .addConstructorArgValue(repositoryInterface)
                        .addPropertyValue("entityOperations", new RuntimeBeanReference(entityOperationsRef));
                String beanName = defaultBeanName(repositoryInterface);
                registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
            }
        }
    }

    private Set<String> resolveBasePackages(AnnotationMetadata metadata, AnnotationAttributes attributes) {
        Set<String> packages = new LinkedHashSet<>();
        String[] basePackages = attributes.getStringArray("basePackages");
        for (String basePackage : basePackages) {
            if (StringUtils.hasText(basePackage)) {
                packages.add(basePackage);
            }
        }
        Class<?>[] basePackageClasses = (Class<?>[]) attributes.get("basePackageClasses");
        if (basePackageClasses != null) {
            for (Class<?> type : basePackageClasses) {
                packages.add(ClassUtils.getPackageName(type));
            }
        }
        if (packages.isEmpty()) {
            packages.add(ClassUtils.getPackageName(metadata.getClassName()));
        }
        return packages;
    }

    private ClassLoader resolveClassLoader(AnnotationMetadata metadata) {
        ClassLoader loader = NovaRepositoriesRegistrar.class.getClassLoader();
        if (loader != null) {
            return loader;
        }
        return ClassUtils.getDefaultClassLoader();
    }

    private String defaultBeanName(Class<?> repositoryInterface) {
        String simpleName = repositoryInterface.getSimpleName();
        if (simpleName.isEmpty()) {
            return repositoryInterface.getName();
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    /**
     * 외부 디버깅용 — 등록되는 base 패키지 셋을 그대로 노출한다.
     */
    public List<String> previewBasePackages(AnnotationMetadata metadata) {
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                metadata.getAnnotationAttributes(EnableNovaRepositories.class.getName()));
        if (attributes == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(resolveBasePackages(metadata, attributes));
    }
}
