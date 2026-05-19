package io.nova.spring.data;

import io.nova.core.ReactiveEntityOperations;
import io.nova.query.Criteria;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * {@link ReactiveCrudRepository} 메서드를 {@link ReactiveEntityOperations}로 위임하는 invocation
 * handler다. {@link NovaRepositoryFactoryBean}이 JDK proxy에 attach한다.
 *
 * <p>위임 디스패치는 메서드 이름 + 파라미터 개수 + (필요 시) 파라미터 타입 조합으로 분기하며,
 * {@code ReactiveCrudRepository}에 정의된 12개 메서드 외 호출은 모두
 * {@link UnsupportedOperationException}으로 거부된다. {@code Object} 메서드는 invocation handler
 * 자체의 identity 기반으로 응답한다.
 */
public final class SimpleReactiveRepository implements InvocationHandler {
    private final Class<?> entityType;
    private final Class<?> idType;
    private final ReactiveEntityOperations entityOperations;

    public SimpleReactiveRepository(
            Class<?> entityType,
            Class<?> idType,
            ReactiveEntityOperations entityOperations
    ) {
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.idType = Objects.requireNonNull(idType, "idType");
        this.entityOperations = Objects.requireNonNull(entityOperations, "entityOperations");
    }

    /**
     * invocation handler 외부에서 read만 가능한 entity type. 테스트 도우미용.
     */
    public Class<?> entityType() {
        return entityType;
    }

    /**
     * invocation handler 외부에서 read만 가능한 id type. 테스트 도우미용.
     */
    public Class<?> idType() {
        return idType;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Class<?> declaring = method.getDeclaringClass();
        if (declaring == Object.class) {
            return invokeObjectMethod(proxy, method, args);
        }
        if (method.isDefault()) {
            throw new UnsupportedOperationException(
                    "default methods on Nova repositories are not supported: " + method);
        }
        String name = method.getName();
        int argCount = method.getParameterCount();
        Class<?>[] paramTypes = method.getParameterTypes();

        switch (name) {
            case "save" -> {
                if (argCount == 1) {
                    return entityOperations.save(args[0]);
                }
            }
            case "saveAll" -> {
                if (argCount == 1 && Iterable.class.isAssignableFrom(paramTypes[0])) {
                    return entityOperations.saveAll((Iterable) args[0]);
                }
            }
            case "findById" -> {
                if (argCount == 1) {
                    return entityOperations.findById((Class) entityType, args[0]);
                }
            }
            case "existsById" -> {
                if (argCount == 1) {
                    return entityOperations.exists(entityType,
                            QuerySpec.empty().where(Criteria.eq("id", args[0])));
                }
            }
            case "findAll" -> {
                if (argCount == 0) {
                    return entityOperations.findAll((Class) entityType, QuerySpec.empty());
                }
                if (argCount == 1 && QuerySpec.class.isAssignableFrom(paramTypes[0])) {
                    return entityOperations.findAll((Class) entityType, (QuerySpec) args[0]);
                }
                if (argCount == 1 && Pageable.class.isAssignableFrom(paramTypes[0])) {
                    return entityOperations.findAll((Class) entityType,
                            QuerySpec.empty().page((Pageable) args[0]));
                }
            }
            case "findAllById" -> {
                if (argCount == 1 && Iterable.class.isAssignableFrom(paramTypes[0])) {
                    return entityOperations.findAllById((Class) entityType, (Iterable) args[0]);
                }
            }
            case "count" -> {
                if (argCount == 0) {
                    return entityOperations.count(entityType, QuerySpec.empty());
                }
            }
            case "deleteById" -> {
                if (argCount == 1) {
                    return entityOperations.deleteById(entityType, args[0]);
                }
            }
            case "delete" -> {
                if (argCount == 1) {
                    return entityOperations.delete(args[0]);
                }
            }
            case "deleteAll" -> {
                if (argCount == 1 && Iterable.class.isAssignableFrom(paramTypes[0])) {
                    return entityOperations.deleteAll((Iterable) args[0]);
                }
            }
            default -> {
                // fall through
            }
        }
        return Mono.error(new UnsupportedOperationException(
                "Unsupported repository method: " + method));
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "NovaRepositoryProxy(" + entityType.getName() + ", id=" + idType.getName() + ")";
            default -> throw new UnsupportedOperationException(
                    "Unsupported Object method: " + method);
        };
    }
}
