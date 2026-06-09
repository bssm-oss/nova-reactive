package io.nova.boot;

import io.nova.annotation.Entity;
import io.nova.schema.DdlAuto;
import io.nova.schema.SchemaInitializer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs {@link SchemaInitializer} on Spring context startup according to the
 * configured {@link DdlAuto} mode and, for {@link DdlAuto#CREATE_DROP},
 * removes the tables again during context shutdown.
 *
 * <p>Uses {@link InitializingBean#afterPropertiesSet()} instead of
 * {@code ApplicationRunner} so the schema is provisioned during the standard
 * Spring container refresh phase. This makes the bootstrap visible to any
 * other bean that runs at refresh time (including {@code ApplicationContextRunner}
 * test harnesses, which never invoke {@code ApplicationRunner}).
 *
 * <p>Entity classes are discovered via Spring's
 * {@link ClassPathScanningCandidateComponentProvider} scanning either the
 * user-supplied {@code nova.entity-packages} list or, when that list is empty,
 * the packages registered by {@link AutoConfigurationPackages} (typically the
 * package containing {@code @SpringBootApplication}).
 */
public class SchemaBootstrapRunner implements InitializingBean, DisposableBean {

    private static final Log log = LogFactory.getLog(SchemaBootstrapRunner.class);

    private final SchemaInitializer schemaInitializer;
    private final NovaProperties properties;
    private final BeanFactory beanFactory;

    /**
     * Captured at startup so {@link #destroy()} can drop exactly what was
     * created — even if the bean factory's auto-configuration packages change
     * before shutdown (unlikely, but defensive).
     */
    private List<Class<?>> bootstrapped = List.of();

    public SchemaBootstrapRunner(
            SchemaInitializer schemaInitializer,
            NovaProperties properties,
            BeanFactory beanFactory) {
        this.schemaInitializer = schemaInitializer;
        this.properties = properties;
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterPropertiesSet() {
        DdlAuto mode = properties.getDdlAuto();
        if (mode == DdlAuto.NONE) {
            return;
        }
        List<Class<?>> entities = discoverEntities();
        if (entities.isEmpty()) {
            log.warn("nova.ddl-auto=" + mode + " but no @Entity classes were discovered in packages "
                    + effectivePackages() + " — schema bootstrap skipped");
            return;
        }
        log.info("nova.ddl-auto=" + mode + " — creating schema for " + entities.size()
                + " entit" + (entities.size() == 1 ? "y" : "ies"));
        // Block until complete: ApplicationRunner contract is synchronous and the rest of the
        // application is allowed to assume the schema exists once startup finishes.
        schemaInitializer.create(entities).block();
        this.bootstrapped = entities;
    }

    @Override
    public void destroy() {
        if (properties.getDdlAuto() != DdlAuto.CREATE_DROP || bootstrapped.isEmpty()) {
            return;
        }
        log.info("nova.ddl-auto=CREATE_DROP — dropping schema for " + bootstrapped.size()
                + " entit" + (bootstrapped.size() == 1 ? "y" : "ies"));
        // Drop in reverse order so child tables go before parents (FK friendly).
        List<Class<?>> reversed = new ArrayList<>(bootstrapped);
        Collections.reverse(reversed);
        schemaInitializer.drop(reversed).block();
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

    private List<Class<?>> discoverEntities() {
        List<String> packages = effectivePackages();
        if (packages.isEmpty()) {
            return List.of();
        }
        // useDefaultFilters=false so only our @Entity filter matches — avoids picking up
        // unrelated @Component / @Service beans that happen to live in scanned packages.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> entities = new ArrayList<>();
        for (String basePackage : packages) {
            scanner.findCandidateComponents(basePackage).forEach(definition -> {
                try {
                    entities.add(Class.forName(definition.getBeanClassName()));
                } catch (ClassNotFoundException unreachable) {
                    // The scanner already loaded the class metadata; this should never happen.
                    throw new IllegalStateException(
                            "Failed to resolve @Entity candidate: " + definition.getBeanClassName(),
                            unreachable);
                }
            });
        }
        return entities;
    }
}
