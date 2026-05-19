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
                    repositoryInterface.getName() + " does not extend " + ReactiveCrudRepository.class.getName());
        }
        ResolvableType resolved = ResolvableType.forClass(repositoryInterface).as(ReactiveCrudRepository.class);
        ResolvableType[] generics = resolved.getGenerics();
        if (generics.length != 2) {
            throw new IllegalStateException(
                    "Could not resolve ReactiveCrudRepository generics on " + repositoryInterface.getName());
        }
        Class<?> entityType = generics[0].resolve();
        Class<?> idType = generics[1].resolve();
        if (entityType == null || idType == null) {
            throw new IllegalStateException(
                    "Unresolved generic type on " + repositoryInterface.getName()
                            + "; entity=" + generics[0] + ", id=" + generics[1]);
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
