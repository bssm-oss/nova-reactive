package io.nova.spring.data;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RepositoryMetadata#resolve(Class)}가 두 가지 generic을 정확히 풀어내는지 검증한다.
 */
class RepositoryMetadataTest {
    private static final EntityMetadataFactory METADATA_FACTORY =
            new EntityMetadataFactory(new DefaultNamingStrategy());

    @Entity(name = "RepositoryMetadataUser")
    @Table(name = "repository_metadata_users")
    static final class User {
        @Id
        final long id;

        User(long id) {
            this.id = id;
        }
    }

    interface UserRepository extends ReactiveCrudRepository<User, Long> {
    }

    interface IndirectUserRepository extends UserRepository {
    }

    interface UnrelatedRepository {
        Mono<Void> ping();
    }

    @Test
    void resolveExtractsEntityAndIdTypes() {
        RepositoryMetadata metadata = RepositoryMetadata.resolve(UserRepository.class);
        assertSame(User.class, metadata.entityType(), "entityType must be User");
        assertSame(Long.class, metadata.idType(), "idType must be Long");
        assertSame(UserRepository.class, metadata.repositoryInterface(), "repositoryInterface preserved");
        assertSame(User.class, METADATA_FACTORY.getEntityMetadata(metadata.entityType()).entityType(),
                "repository domain must build EntityMetadata");
    }

    @Test
    void resolveFollowsIndirectInheritance() {
        RepositoryMetadata metadata = RepositoryMetadata.resolve(IndirectUserRepository.class);
        assertSame(User.class, metadata.entityType());
        assertSame(Long.class, metadata.idType());
    }

    @Test
    void resolveRejectsNonReactiveCrudRepository() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> RepositoryMetadata.resolve(UnrelatedRepository.class));
        assertEquals(true, exception.getMessage().contains("does not extend"),
                "exception should explain why repository is rejected, got: " + exception.getMessage());
    }
}
