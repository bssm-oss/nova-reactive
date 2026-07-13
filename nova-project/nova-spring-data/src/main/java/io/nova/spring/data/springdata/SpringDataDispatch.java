package io.nova.spring.data.springdata;

import io.nova.core.ReactiveEntityOperations;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;

/**
 * {@code ReactiveCrudRepository}의 Spring Data 표준 브릿지 오버로드
 * ({@code findAll(Sort)}/{@code findAll(Pageable)}/{@code findAll(QuerySpec, Pageable)}/
 * {@code findSlice(Pageable)}/{@code findSlice(QuerySpec, Pageable)})를 실행하는 dispatcher다.
 *
 * <p><b>격리(isolation) 계약:</b> 이 클래스는 {@code org.springframework.data.domain.*} 타입을
 * 직접 참조하는 유일한 dispatch 지점이다. {@code SimpleReactiveRepository}는 파라미터 타입의
 * <em>클래스 이름 문자열</em>만 비교(클래스 리터럴/캐스트 없음)하여 Spring 브릿지 메서드를
 * 판별하고, 판별된 경우에만 이 클래스로 위임한다. 따라서 Spring Data 표준 타입을 쓰지 않는
 * 소비자의 코드 경로에서는 이 클래스가 절대 로드되지 않으며 {@code spring-data-commons}가
 * 런타임 클래스패스에 없어도 core repository 동작에 영향이 없다.
 */
public final class SpringDataDispatch {

    private SpringDataDispatch() {
    }

    /**
     * 주어진 파라미터 타입이 Spring Data {@code Pageable}인지 클래스 이름으로 판별한다(클래스 로드
     * 없음).
     */
    public static boolean isSpringPageable(Class<?> type) {
        return type.getName().equals("org.springframework.data.domain.Pageable");
    }

    /**
     * 주어진 파라미터 타입이 Spring Data {@code Sort}인지 클래스 이름으로 판별한다(클래스 로드
     * 없음).
     */
    public static boolean isSpringSort(Class<?> type) {
        return type.getName().equals("org.springframework.data.domain.Sort");
    }

    /**
     * Spring 브릿지 오버로드를 실제로 실행한다. 호출자는 사전에 {@link #isSpringPageable}/
     * {@link #isSpringSort}로 Spring 파라미터를 확인한 뒤에만 진입해야 한다.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object dispatch(
            Class<?> entityType, ReactiveEntityOperations operations, Method method, Object[] args) {
        String name = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        int argCount = method.getParameterCount();

        if (name.equals("findAll")) {
            if (argCount == 1 && isSpringSort(paramTypes[0])) {
                return findAllSorted(entityType, operations,
                        (org.springframework.data.domain.Sort) args[0]);
            }
            if (argCount == 1 && isSpringPageable(paramTypes[0])) {
                return findAllPaged(entityType, operations, QuerySpec.empty(),
                        (org.springframework.data.domain.Pageable) args[0]);
            }
            if (argCount == 2
                    && QuerySpec.class.isAssignableFrom(paramTypes[0])
                    && isSpringPageable(paramTypes[1])) {
                return findAllPaged(entityType, operations, (QuerySpec) args[0],
                        (org.springframework.data.domain.Pageable) args[1]);
            }
        } else if (name.equals("findSlice")) {
            if (argCount == 1 && isSpringPageable(paramTypes[0])) {
                return findSlicePaged(entityType, operations, QuerySpec.empty(),
                        (org.springframework.data.domain.Pageable) args[0]);
            }
            if (argCount == 2
                    && QuerySpec.class.isAssignableFrom(paramTypes[0])
                    && isSpringPageable(paramTypes[1])) {
                return findSlicePaged(entityType, operations, (QuerySpec) args[0],
                        (org.springframework.data.domain.Pageable) args[1]);
            }
        }
        return Mono.error(new UnsupportedOperationException(
                "Unsupported Spring Data bridge method: " + method));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object findAllSorted(
            Class<?> entityType, ReactiveEntityOperations operations,
            org.springframework.data.domain.Sort springSort) {
        // Flux.defer로 감싸 정렬 변환(fail-fast 가능)이 조립 시점 동기 throw가 아니라 구독 시점
        // onError로 전파되도록 한다.
        return Flux.defer(() -> {
            Sort novaSort = SpringDataSorts.toNova(springSort);
            QuerySpec spec = novaSort == null ? QuerySpec.empty() : QuerySpec.empty().orderBy(novaSort);
            return operations.findAll((Class) entityType, spec);
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object findAllPaged(
            Class<?> entityType, ReactiveEntityOperations operations, QuerySpec baseSpec,
            org.springframework.data.domain.Pageable springPageable) {
        return Mono.defer(() -> {
            QuerySpec spec = applySpringSort(baseSpec, springPageable.getSort());
            if (springPageable.isUnpaged()) {
                return operations.findAll((Class) entityType, spec)
                        .collectList()
                        // 요청 pageable을 그대로 실어 정렬/pageable 메타데이터를 보존한다.
                        .map(list -> new org.springframework.data.domain.PageImpl<Object>(
                                (java.util.List<Object>) list, springPageable, ((java.util.List) list).size()));
            }
            Pageable novaPageable = SpringDataPageables.toNova(springPageable);
            return ((Mono) operations.findAll((Class) entityType, spec, novaPageable))
                    .map(page -> SpringDataPageables.toSpring(
                            (io.nova.query.Page) page, springPageable));
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object findSlicePaged(
            Class<?> entityType, ReactiveEntityOperations operations, QuerySpec baseSpec,
            org.springframework.data.domain.Pageable springPageable) {
        return Mono.defer(() -> {
            if (springPageable.isUnpaged()) {
                return Mono.error(new IllegalArgumentException(
                        "findSlice requires a paged Pageable; Pageable.unpaged() is not supported for "
                                + "slice queries because a slice must have a page-size limit."));
            }
            QuerySpec spec = applySpringSort(baseSpec, springPageable.getSort());
            Pageable novaPageable = SpringDataPageables.toNova(springPageable);
            return ((Mono) operations.findSlice((Class) entityType, spec, novaPageable))
                    .map(slice -> SpringDataPageables.toSpring(
                            (io.nova.query.Slice) slice, springPageable));
        });
    }

    /**
     * Spring {@code Sort}가 지정되어 있으면 Nova 정렬로 변환해 명세에 적용하고, 아니면
     * ({@code Sort.unsorted()}) 명세의 기존 정렬을 보존한다.
     */
    private static QuerySpec applySpringSort(
            QuerySpec baseSpec, org.springframework.data.domain.Sort springSort) {
        Sort novaSort = SpringDataSorts.toNova(springSort);
        if (novaSort == null) {
            return baseSpec;
        }
        return baseSpec.orderBy(novaSort);
    }
}
