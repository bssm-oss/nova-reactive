package io.nova.boot;

import io.nova.spring.data.ReactiveCrudRepository;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

/**
 * Discovers Nova repositories from Spring Boot's application packages. An explicit
 * {@code @EnableNovaRepositories} declaration remains the escape hatch for custom packages
 * and bean references and causes this automatic path to back off completely.
 */
@AutoConfiguration(after = NovaAutoConfiguration.class)
@ConditionalOnClass(ReactiveCrudRepository.class)
@ConditionalOnProperty(prefix = "nova.repositories", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class NovaRepositoriesAutoConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static NovaRepositoryAutoRegistrar novaRepositoryAutoRegistrar() {
        return new NovaRepositoryAutoRegistrar();
    }
}
