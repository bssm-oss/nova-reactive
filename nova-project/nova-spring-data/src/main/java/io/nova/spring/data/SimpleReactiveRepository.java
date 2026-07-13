package io.nova.spring.data;

import io.nova.core.ReactiveEntityOperations;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.spring.data.derived.DerivedQueries;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

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
    private final DerivedQueries derivedQueries;

    public SimpleReactiveRepository(
            Class<?> entityType,
            Class<?> idType,
            ReactiveEntityOperations entityOperations
    ) {
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.idType = Objects.requireNonNull(idType, "idType");
        this.entityOperations = Objects.requireNonNull(entityOperations, "entityOperations");
        this.derivedQueries = new DerivedQueries(entityType, entityOperations);
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

        // Spring Data 표준 브릿지 오버로드(파라미터가 org.springframework.data.domain.Pageable/Sort)는
        // 전용 dispatcher로 위임한다. 여기서는 파라미터 타입의 이름 문자열만 비교하므로(클래스 리터럴
        // 없음) Spring 타입/브릿지 클래스를 로드하지 않는다 — Spring Data 표준 타입을 쓰지 않는
        // 소비자의 경로에서는 spring-data-commons가 클래스패스에 없어도 안전하다.
        for (Class<?> paramType : paramTypes) {
            String paramName = paramType.getName();
            if (paramName.equals("org.springframework.data.domain.Pageable")
                    || paramName.equals("org.springframework.data.domain.Sort")) {
                return io.nova.spring.data.springdata.SpringDataDispatch.dispatch(
                        entityType, entityOperations, method, args);
            }
        }

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
                    return entityOperations.existsById(entityType, args[0]);
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
                if (argCount == 2
                        && QuerySpec.class.isAssignableFrom(paramTypes[0])
                        && Pageable.class.isAssignableFrom(paramTypes[1])) {
                    return entityOperations.findAll((Class) entityType,
                            (QuerySpec) args[0], (Pageable) args[1]);
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
                // fall through to derived query parsing.
            }
        }
        // fixed-name switch가 처리하지 못한 호출은 derived query 파서에 한 번 더 기회를 준다.
        // 파서가 메서드 이름을 인식하지 못하면 (Optional.empty) 진짜 unsupported,
        // 인식했지만 잘못된 사용이면 (IllegalArgumentException) 명시적인 메시지로 fail-fast.
        Optional<Object> derived = derivedQueries.tryDispatch(method, args);
        if (derived.isPresent()) {
            return derived.get();
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
