package io.nova.spring.data;

import org.springframework.core.ResolvableType;

import java.util.Objects;

/**
 * {@link ReactiveCrudRepository}의 {@code <T, ID>} 제네릭을 raw class로 풀어 가지고 있는
 * 불변 메타데이터다. invocation handler가 {@code entityType}을 {@link io.nova.core.ReactiveEntityOperations}
 * 호출에 그대로 전달하기 위해 사용된다.
 */
public final class RepositoryMetadata {
    private final Class<?> repositoryInterface;
    private final Class<?> entityType;
    private final Class<?> idType;

    private RepositoryMetadata(Class<?> repositoryInterface, Class<?> entityType, Class<?> idType) {
        this.repositoryInterface = Objects.requireNonNull(repositoryInterface, "repositoryInterface");
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.idType = Objects.requireNonNull(idType, "idType");
    }

    /**
     * 주어진 repository 인터페이스의 {@link ReactiveCrudRepository} 제네릭을 해석한다.
     * 인터페이스가 {@code ReactiveCrudRepository}를 직접 또는 간접 상속하지 않거나, 제네릭이
     * 풀리지 않으면 {@link IllegalStateException}을 던진다.
     */
    public static RepositoryMetadata resolve(Class<?> repositoryInterface) {
        Objects.requireNonNull(repositoryInterface, "repositoryInterface");
        if (!ReactiveCrudRepository.class.isAssignableFrom(repositoryInterface)) {
            throw new IllegalStateException(
                    repositoryInterface.getName() + " does not extend " + ReactiveCrudRepository.class.getName()
                            + "; Nova repository interfaces must extend ReactiveCrudRepository<Entity, Id> "
                            + "(directly or via another repository interface) to be picked up by "
                            + "@EnableNovaRepositories");
        }
        ResolvableType resolved = ResolvableType.forClass(repositoryInterface).as(ReactiveCrudRepository.class);
        ResolvableType[] generics = resolved.getGenerics();
        if (generics.length != 2) {
            throw new IllegalStateException(
                    "Could not resolve the <Entity, Id> generic parameters of ReactiveCrudRepository on "
                            + repositoryInterface.getName()
                            + "; declare them directly, e.g. `interface FooRepository extends "
                            + "ReactiveCrudRepository<Foo, Long>`");
        }
        Class<?> entityType = generics[0].resolve();
        Class<?> idType = generics[1].resolve();
        if (entityType == null || idType == null) {
            throw new IllegalStateException(
                    "Could not resolve concrete entity/id classes for ReactiveCrudRepository<"
                            + generics[0] + ", " + generics[1] + "> on " + repositoryInterface.getName()
                            + "; use concrete types instead of a further type variable or wildcard, e.g. "
                            + "`interface FooRepository extends ReactiveCrudRepository<Foo, Long>`");
        }
        return new RepositoryMetadata(repositoryInterface, entityType, idType);
    }

    public Class<?> repositoryInterface() {
        return repositoryInterface;
    }

    public Class<?> entityType() {
        return entityType;
    }

    public Class<?> idType() {
        return idType;
    }
}
