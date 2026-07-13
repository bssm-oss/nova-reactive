package io.nova.spring.data.query;

import io.nova.spring.data.Modifying;
import io.nova.spring.data.Param;
import io.nova.spring.data.Query;
import io.nova.spring.data.springdata.SpringDataDispatch;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code @Query} 애너테이션이 붙은 repository 메서드를 해석한 불변 메타데이터다. 쿼리 문자열/언어(JPQL vs
 * native)/{@link Modifying} 여부/반환 형태(Flux·Mono·Page·Slice)와 파라미터 바인딩 계획을 담는다.
 *
 * <p>Spring 타입({@code Pageable}/{@code Sort}/{@code Param})은 <b>클래스/애너테이션 이름 문자열</b>로만
 * 탐지하며(클래스 리터럴 없음), 실제 Spring 타입 참조는 실행기가 {@code springdata} 패키지의 헬퍼로만
 * 수행한다 — 이로써 Spring Data를 쓰지 않는 소비자 경로에서 Spring 타입 강제 로드를 피한다.
 */
public final class AnnotatedQueryMethod {

    /** 메서드 반환 형태. */
    public enum Shape {
        FLUX, MONO_SINGLE, NOVA_PAGE, NOVA_SLICE, SPRING_PAGE, SPRING_SLICE, MODIFYING
    }

    /** 바인딩 가능한(=Pageable/Sort가 아닌) 파라미터 하나. */
    public record Bindable(int argIndex, String name, int positional) {
    }

    private static final String SPRING_PARAM = "org.springframework.data.repository.query.Param";
    private static final String SPRING_MODIFYING = "org.springframework.data.jpa.repository.Modifying";
    private static final String NOVA_PAGEABLE = "io.nova.query.Pageable";
    private static final String NOVA_SLICE = "io.nova.query.Slice";
    private static final String NOVA_PAGE = "io.nova.query.Page";
    private static final String NOVA_SORT = "io.nova.query.Sort";
    private static final String SPRING_PAGE_NAME = "org.springframework.data.domain.Page";
    private static final String SPRING_SLICE_NAME = "org.springframework.data.domain.Slice";

    private final String query;
    private final boolean nativeQuery;
    private final boolean modifying;
    private final String countQuery;
    private final Shape shape;
    private final Class<?> elementType;
    private final Class<?> modifyingResultType;
    private final List<Bindable> bindables;
    private final int pageableArgIndex;
    private final boolean pageableSpring;

    private AnnotatedQueryMethod(String query, boolean nativeQuery, boolean modifying, String countQuery,
                                 Shape shape, Class<?> elementType, Class<?> modifyingResultType,
                                 List<Bindable> bindables, int pageableArgIndex, boolean pageableSpring) {
        this.query = query;
        this.nativeQuery = nativeQuery;
        this.modifying = modifying;
        this.countQuery = countQuery;
        this.shape = shape;
        this.elementType = elementType;
        this.modifyingResultType = modifyingResultType;
        this.bindables = List.copyOf(bindables);
        this.pageableArgIndex = pageableArgIndex;
        this.pageableSpring = pageableSpring;
    }

    /**
     * 메서드에 {@code @Query}가 없으면 {@code null}, 있으면 해석된 메타데이터를 반환한다. 지원 범위를
     * 벗어난 정의는 {@link AnnotatedQueryException}으로 fail-fast 한다.
     */
    public static AnnotatedQueryMethod parse(Method method, Class<?> entityType) {
        Query annotation = method.getAnnotation(Query.class);
        if (annotation == null) {
            return null;
        }
        String query = annotation.value();
        if (query == null || query.isBlank()) {
            throw new AnnotatedQueryException("@Query value must not be blank on " + method);
        }
        query = query.trim();
        boolean nativeQuery = annotation.nativeQuery();
        boolean modifying = hasModifying(method);
        String countQuery = annotation.countQuery() == null ? "" : annotation.countQuery().trim();

        // 파라미터 분류: Pageable(1개까지) / Sort(거부) / 바인딩 가능.
        Parameter[] params = method.getParameters();
        List<Bindable> bindables = new ArrayList<>();
        int pageableArgIndex = -1;
        boolean pageableSpring = false;
        int positional = 0;
        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();
            String typeName = type.getName();
            if (NOVA_SORT.equals(typeName) || SpringDataDispatch.isSpringSort(type)) {
                throw new AnnotatedQueryException(
                        "@Query does not support a Sort parameter on " + method
                                + "; express ordering with ORDER BY inside the query string.");
            }
            if (NOVA_PAGEABLE.equals(typeName) || SpringDataDispatch.isSpringPageable(type)) {
                if (pageableArgIndex >= 0) {
                    throw new AnnotatedQueryException("@Query allows at most one Pageable parameter on " + method);
                }
                pageableArgIndex = i;
                pageableSpring = SpringDataDispatch.isSpringPageable(type);
                continue;
            }
            positional++;
            bindables.add(new Bindable(i, paramName(params[i]), positional));
        }

        Shape shape;
        Class<?> elementType;
        Class<?> modifyingResultType = null;
        if (modifying) {
            shape = Shape.MODIFYING;
            elementType = null;
            modifyingResultType = resolveModifyingResultType(method);
            if (pageableArgIndex >= 0) {
                throw new AnnotatedQueryException("@Modifying @Query must not take a Pageable parameter on " + method);
            }
        } else {
            ResolvedReturn resolved = resolveReturn(method);
            shape = resolved.shape();
            elementType = resolved.elementType();
            validateNonModifying(method, shape, elementType, entityType, pageableArgIndex, pageableSpring);
        }

        if (nativeQuery) {
            validateNativeSupported(method, shape, pageableArgIndex);
        } else {
            validateJpqlModifying(method, modifying, query);
        }

        return new AnnotatedQueryMethod(query, nativeQuery, modifying, countQuery, shape, elementType,
                modifyingResultType, bindables, pageableArgIndex, pageableSpring);
    }

    // ---------------------------------------------------------------------------------------------
    // Return type resolution
    // ---------------------------------------------------------------------------------------------

    private record ResolvedReturn(Shape shape, Class<?> elementType) {
    }

    private static ResolvedReturn resolveReturn(Method method) {
        Class<?> rawReturn = method.getReturnType();
        String rawName = rawReturn.getName();
        Type generic = method.getGenericReturnType();
        if ("reactor.core.publisher.Flux".equals(rawName)) {
            return new ResolvedReturn(Shape.FLUX, firstTypeArg(generic));
        }
        if ("reactor.core.publisher.Mono".equals(rawName)) {
            Type inner = firstTypeArgType(generic);
            Class<?> innerRaw = rawOf(inner);
            if (innerRaw != null) {
                String innerName = innerRaw.getName();
                if (NOVA_PAGE.equals(innerName)) {
                    return new ResolvedReturn(Shape.NOVA_PAGE, firstTypeArg(inner));
                }
                if (NOVA_SLICE.equals(innerName)) {
                    return new ResolvedReturn(Shape.NOVA_SLICE, firstTypeArg(inner));
                }
                if (SPRING_PAGE_NAME.equals(innerName)) {
                    return new ResolvedReturn(Shape.SPRING_PAGE, firstTypeArg(inner));
                }
                if (SPRING_SLICE_NAME.equals(innerName)) {
                    return new ResolvedReturn(Shape.SPRING_SLICE, firstTypeArg(inner));
                }
            }
            return new ResolvedReturn(Shape.MONO_SINGLE, rawOr(inner, Object.class));
        }
        throw new AnnotatedQueryException(
                "@Query method must return Mono or Flux on " + method + " (found " + rawName + ")");
    }

    private static void validateNonModifying(Method method, Shape shape, Class<?> elementType,
                                             Class<?> entityType, int pageableArgIndex, boolean pageableSpring) {
        boolean entityElement = elementType != null && (elementType.equals(entityType) || elementType == Object.class);
        switch (shape) {
            case NOVA_PAGE, NOVA_SLICE -> {
                requirePageable(method, pageableArgIndex);
                if (pageableSpring) {
                    throw new AnnotatedQueryException(
                            "Nova Page/Slice return requires a Nova Pageable parameter on " + method);
                }
                requireEntityPaging(method, entityElement);
            }
            case SPRING_PAGE, SPRING_SLICE -> {
                requirePageable(method, pageableArgIndex);
                if (!pageableSpring) {
                    throw new AnnotatedQueryException(
                            "Spring Page/Slice return requires a Spring Data Pageable parameter on " + method);
                }
                requireEntityPaging(method, entityElement);
            }
            case MONO_SINGLE -> {
                if (pageableArgIndex >= 0) {
                    throw new AnnotatedQueryException(
                            "@Query returning Mono<T> must not take a Pageable parameter on " + method
                                    + "; return Mono<Page<T>>/Mono<Slice<T>> for paging.");
                }
            }
            case FLUX -> {
                if (pageableArgIndex >= 0 && !entityElement) {
                    throw new AnnotatedQueryException(
                            "@Query with a Pageable parameter returning Flux must select the entity type on " + method);
                }
            }
            default -> {
                // MODIFYING handled elsewhere.
            }
        }
    }

    private static void requireEntityPaging(Method method, boolean entityElement) {
        if (!entityElement) {
            throw new AnnotatedQueryException(
                    "Paged @Query (Page/Slice) is only supported for entity-returning queries on " + method);
        }
    }

    private static void requirePageable(Method method, int pageableArgIndex) {
        if (pageableArgIndex < 0) {
            throw new AnnotatedQueryException(
                    "@Query returning Page/Slice requires a Pageable parameter on " + method);
        }
    }

    private static void validateNativeSupported(Method method, Shape shape, int pageableArgIndex) {
        if (shape == Shape.NOVA_PAGE || shape == Shape.NOVA_SLICE
                || shape == Shape.SPRING_PAGE || shape == Shape.SPRING_SLICE || pageableArgIndex >= 0) {
            throw new AnnotatedQueryException(
                    "native @Query with Pageable/Page/Slice is not supported in v1 on " + method
                            + "; use a JPQL @Query for reactive paging.");
        }
    }

    private static void validateJpqlModifying(Method method, boolean modifying, String query) {
        String head = leadingKeyword(query);
        boolean isSelect = head.equals("SELECT");
        if (modifying && isSelect) {
            throw new AnnotatedQueryException(
                    "@Modifying @Query must be an UPDATE/DELETE statement, not SELECT, on " + method);
        }
        if (!modifying && !isSelect && (head.equals("UPDATE") || head.equals("DELETE") || head.equals("INSERT"))) {
            throw new AnnotatedQueryException(
                    "@Query with a bulk " + head + " statement requires @Modifying on " + method);
        }
    }

    private static String leadingKeyword(String query) {
        String trimmed = query.trim();
        int i = 0;
        while (i < trimmed.length() && !Character.isWhitespace(trimmed.charAt(i))) {
            i++;
        }
        return trimmed.substring(0, i).toUpperCase();
    }

    private static Class<?> resolveModifyingResultType(Method method) {
        Class<?> raw = method.getReturnType();
        if (!"reactor.core.publisher.Mono".equals(raw.getName())) {
            throw new AnnotatedQueryException("@Modifying @Query must return Mono<Long|Integer|Void> on " + method);
        }
        Class<?> inner = firstTypeArg(method.getGenericReturnType());
        if (inner == Long.class || inner == Integer.class || inner == Void.class) {
            return inner;
        }
        throw new AnnotatedQueryException(
                "@Modifying @Query must return Mono<Long>, Mono<Integer>, or Mono<Void> on " + method
                        + " (found Mono<" + (inner == null ? "?" : inner.getSimpleName()) + ">)");
    }

    // ---------------------------------------------------------------------------------------------
    // Annotation / generic helpers
    // ---------------------------------------------------------------------------------------------

    private static boolean hasModifying(Method method) {
        if (method.isAnnotationPresent(Modifying.class)) {
            return true;
        }
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation.annotationType().getName().equals(SPRING_MODIFYING)) {
                return true;
            }
        }
        return false;
    }

    /** {@link Param}(Nova) 또는 Spring {@code @Param}(이름 문자열로 탐지)에서 바인딩 이름을 읽는다. */
    private static String paramName(Parameter parameter) {
        Param nova = parameter.getAnnotation(Param.class);
        if (nova != null) {
            return nova.value();
        }
        for (Annotation annotation : parameter.getAnnotations()) {
            if (annotation.annotationType().getName().equals(SPRING_PARAM)) {
                return springParamValue(annotation);
            }
        }
        return null;
    }

    private static String springParamValue(Annotation annotation) {
        try {
            Object value = annotation.annotationType().getMethod("value").invoke(annotation);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException exception) {
            throw new AnnotatedQueryException(
                    "Could not read Spring @Param value: " + exception.getMessage());
        }
    }

    private static Class<?> firstTypeArg(Type type) {
        return rawOr(firstTypeArgType(type), Object.class);
    }

    private static Type firstTypeArgType(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length > 0) {
                return args[0];
            }
        }
        return null;
    }

    private static Class<?> rawOf(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    private static Class<?> rawOr(Type type, Class<?> fallback) {
        Class<?> raw = rawOf(type);
        return raw == null ? fallback : raw;
    }

    // ---------------------------------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------------------------------

    public String query() {
        return query;
    }

    public boolean nativeQuery() {
        return nativeQuery;
    }

    public boolean modifying() {
        return modifying;
    }

    public String countQuery() {
        return countQuery;
    }

    public Shape shape() {
        return shape;
    }

    public Class<?> elementType() {
        return elementType;
    }

    public Class<?> modifyingResultType() {
        return modifyingResultType;
    }

    public List<Bindable> bindables() {
        return bindables;
    }

    public int pageableArgIndex() {
        return pageableArgIndex;
    }

    public boolean pageableSpring() {
        return pageableSpring;
    }

    public boolean hasPageable() {
        return pageableArgIndex >= 0;
    }

    /** 결과 원소가 repository 엔티티 타입(또는 auto-detect용 Object)인지. */
    public boolean isEntityElement(Class<?> entityType) {
        return elementType != null && (elementType.equals(entityType) || elementType == Object.class);
    }
}
