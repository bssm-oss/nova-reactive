package io.nova.boot;

import jakarta.persistence.Entity;
import jakarta.persistence.Converter;
import io.nova.metadata.EntityMetadataFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.List;
import java.util.ArrayList;

/**
 * Eagerly builds {@link io.nova.metadata.EntityMetadata} for every {@code @Entity} discovered in the
 * configured {@code nova.entity-packages} (or {@link AutoConfigurationPackages} when unset) at context
 * refresh — mirroring how a JPA persistence unit knows all of its entities at bootstrap.
 *
 * <p>This matters for SINGLE_TABLE inheritance: a polymorphic root query ({@code findAll(Vehicle.class)})
 * can only dispatch each row to the right concrete subtype if every subtype's metadata has already been
 * built and registered with the {@link EntityMetadataFactory}. Without this preload, the hierarchy would
 * only be fully known after each subtype was touched individually. It runs regardless of
 * {@code nova.ddl-auto}, so polymorphic reads work even with the default {@code none}.
 *
 * <p>Entity metadata build errors propagate and fail startup (fail-fast, as JPA validates all mapped
 * classes at bootstrap) rather than surfacing lazily on first query.
 */
public class NovaEntityPreloadRunner implements InitializingBean {

    private static final Log log = LogFactory.getLog(NovaEntityPreloadRunner.class);

    private final EntityMetadataFactory metadataFactory;
    private final NovaProperties properties;
    private final BeanFactory beanFactory;

    public NovaEntityPreloadRunner(
            EntityMetadataFactory metadataFactory,
            NovaProperties properties,
            BeanFactory beanFactory) {
        this.metadataFactory = metadataFactory;
        this.properties = properties;
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> packages = effectivePackages();
        if (packages.isEmpty()) {
            return;
        }
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Converter.class));
        List<Class<?>> discovered = new ArrayList<>();
        for (String basePackage : packages) {
            for (var definition : scanner.findCandidateComponents(basePackage)) {
                try {
                    discovered.add(Class.forName(definition.getBeanClassName()));
                } catch (ClassNotFoundException unreachable) {
                    throw new IllegalStateException(
                            "Failed to resolve Nova managed-class candidate: " + definition.getBeanClassName(),
                            unreachable);
                }
            }
        }
        // Converter registration must precede every entity metadata build so auto-apply is deterministic.
        metadataFactory.registerManagedClasses(discovered);
        int count = 0;
        for (Class<?> entityType : discovered) {
            if (entityType.isAnnotationPresent(Entity.class)) {
                // Build (and cache + register hierarchy membership) every entity's metadata up front.
                metadataFactory.getEntityMetadata(entityType);
                count++;
            }
        }
        if (count > 0) {
            log.debug("Nova preloaded metadata for " + count + " @Entity class(es) in " + packages);
        }
    }

    private List<String> effectivePackages() {
        List<String> configured = properties.getEntityPackages();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        if (AutoConfigurationPackages.has(beanFactory)) {
            return AutoConfigurationPackages.get(beanFactory);
        }
        return List.of();
    }
}
