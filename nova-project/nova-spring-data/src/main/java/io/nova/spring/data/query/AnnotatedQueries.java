package io.nova.spring.data.query;

import io.nova.core.ReactiveEntityOperations;
import io.nova.query.NativeQuery;
import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.Slice;
import io.nova.query.jpql.JpqlExecutor;
import io.nova.query.jpql.JpqlQuery;
import io.nova.spring.data.springdata.SpringDataQuerySupport;
import io.nova.sql.Dialect;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code @Query} 지원의 외부 진입점. {@link AnnotatedQueryMethod} 파싱과 실행을 캡슐화해 호출자
 * ({@link io.nova.spring.data.SimpleReactiveRepository})는 method/args만 넘기면 된다.
 *
 * <p>JPQL {@code @Query}는 Wave1 {@link JpqlExecutor}/{@link JpqlQuery}로, native {@code @Query}는
 * core의 {@code queryNative}/{@code executeNative}/{@code findAll(Class, CompiledQuery, …)} 진입점으로
 * 실행한다 — 코어 로직은 호출만 하고 수정하지 않는다.
 *
 * <p><b>optionality:</b> {@code jpqlExecutor}/{@code dialect}는 nullable이다. 이 클래스는 Spring Data
 * 타입을 자신의 bytecode에 직접 참조하지 않고 {@link SpringDataQuerySupport}(Object 시그니처)로만
 * 다룬다. {@code @Query}를 쓰지 않는 repository는 이 경로에 진입하지 않는다.
 */
public final class AnnotatedQueries {

    private final Class<?> entityType;
    private final ReactiveEntityOperations operations;
    private final JpqlExecutor jpqlExecutor;
    private final Dialect dialect;

    public AnnotatedQueries(Class<?> entityType, ReactiveEntityOperations operations,
                            JpqlExecutor jpqlExecutor, Dialect dialect) {
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.jpqlExecutor = jpqlExecutor;
        this.dialect = dialect;
    }

    /**
     * 메서드에 {@code @Query}가 있으면 실행 결과 publisher를 {@link Optional}로 반환한다. 없으면
     * {@link Optional#empty()} — 호출자는 기존 dispatch 경로로 fallthrough한다. 정의 오류는
     * {@link AnnotatedQueryException}으로 즉시 fail-fast하며, 실행 시점 오류는 반환 publisher의
     * onError 신호로 전파된다.
     */
    public Optional<Object> tryDispatch(Method method, Object[] args) {
        AnnotatedQueryMethod meta = AnnotatedQueryMethod.parse(method, entityType);
        if (meta == null) {
            return Optional.empty();
        }
        Object[] safeArgs = args == null ? new Object[0] : args;
        return Optional.of(meta.nativeQuery() ? executeNative(meta, safeArgs) : executeJpql(meta, safeArgs));
    }

    // ---------------------------------------------------------------------------------------------
    // JPQL
    // ---------------------------------------------------------------------------------------------

    private Object executeJpql(AnnotatedQueryMethod meta, Object[] args) {
        if (jpqlExecutor == null) {
            return failMissingJpql(meta);
        }
        return switch (meta.shape()) {
            case MODIFYING -> adaptModifying(
                    Mono.defer(() -> jpqlQuery(meta, args, null).executeUpdate()), meta.modifyingResultType());
            case FLUX -> Flux.defer(() -> pagedEntityQueryOrPlain(meta, args).getResultList());
            case MONO_SINGLE -> Mono.defer(() -> jpqlQuery(meta, args, resultTypeFor(meta))
                    .getResultList().next());
            case NOVA_PAGE -> novaPage(meta, args);
            case NOVA_SLICE -> novaSlice(meta, args);
            case SPRING_PAGE -> Mono.defer(() -> novaPage(meta, args)
                    .map(page -> SpringDataQuerySupport.springPage(
                            page.content(), page.totalElements(), args[meta.pageableArgIndex()])));
            case SPRING_SLICE -> Mono.defer(() -> novaSlice(meta, args)
                    .map(slice -> SpringDataQuerySupport.springSlice(
                            slice.content(), slice.hasNext(), args[meta.pageableArgIndex()])));
        };
    }

    /** FLUX 형태에서 Pageable이 있으면 limit/offset을 적용한 엔티티 쿼리를, 없으면 평범한 쿼리를 만든다. */
    @SuppressWarnings("rawtypes")
    private JpqlQuery pagedEntityQueryOrPlain(AnnotatedQueryMethod meta, Object[] args) {
        if (!meta.hasPageable()) {
            return jpqlQuery(meta, args, resultTypeFor(meta));
        }
        Pageable pageable = novaPageable(meta, args);
        return pagedEntityQuery(meta, args, pageable.offset(), pageable.limit());
    }

    @SuppressWarnings("rawtypes")
    private Mono<Page<Object>> novaPage(AnnotatedQueryMethod meta, Object[] args) {
        return Mono.defer(() -> {
            Pageable pageable = novaPageable(meta, args);
            Mono<List<Object>> content = pagedEntityQuery(meta, args, pageable.offset(), pageable.limit())
                    .getResultList().cast(Object.class).collectList();
            Mono<Long> total = totalCount(meta, args);
            return Mono.zip(content, total)
                    .map(t -> new Page<>(t.getT1(), t.getT2(), pageable));
        });
    }

    @SuppressWarnings("rawtypes")
    private Mono<Slice<Object>> novaSlice(AnnotatedQueryMethod meta, Object[] args) {
        return Mono.defer(() -> {
            Pageable pageable = novaPageable(meta, args);
            int limit = pageable.limit();
            Flux<Object> rows = pagedEntityQuery(meta, args, pageable.offset(), limit + 1)
                    .getResultList().cast(Object.class);
            return rows.collectList().map(list -> {
                boolean hasNext = list.size() > limit;
                List<Object> trimmed = hasNext ? new ArrayList<>(list.subList(0, limit)) : list;
                return new Slice<>(trimmed, pageable, hasNext);
            });
        });
    }

    private Mono<Long> totalCount(AnnotatedQueryMethod meta, Object[] args) {
        if (!meta.countQuery().isBlank()) {
            return bindParameters(jpqlExecutor.createQuery(meta.countQuery()), meta, args)
                    .getSingleResult()
                    .map(value -> ((Number) value).longValue());
        }
        // count 쿼리 미지정: 원 쿼리를 페이징 없이 실행한 결과 개수로 total 계산.
        return jpqlQuery(meta, args, entityType).getResultList().count();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private JpqlQuery pagedEntityQuery(AnnotatedQueryMethod meta, Object[] args, long offset, int limit) {
        JpqlQuery query = jpqlQuery(meta, args, entityType);
        query.setMaxResults(limit);
        if (offset > 0) {
            query.setFirstResult((int) offset);
        }
        return query;
    }

    /** result type을 지정해 JpqlQuery를 만들고 파라미터를 바인딩한다. resultType이 null이면 Object. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private JpqlQuery jpqlQuery(AnnotatedQueryMethod meta, Object[] args, Class<?> resultType) {
        JpqlQuery query = resultType == null
                ? jpqlExecutor.createQuery(meta.query())
                : jpqlExecutor.createQuery(meta.query(), resultType);
        return bindParameters(query, meta, args);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private JpqlQuery bindParameters(JpqlQuery query, AnnotatedQueryMethod meta, Object[] args) {
        for (AnnotatedQueryMethod.Bindable bindable : meta.bindables()) {
            Object value = args[bindable.argIndex()];
            query.setParameter(bindable.positional(), value);
            if (bindable.name() != null) {
                query.setParameter(bindable.name(), value);
            }
        }
        return query;
    }

    /** 엔티티 결과면 entityType, 스칼라면 elementType(Object이면 auto-detect)로 result type을 정한다. */
    private Class<?> resultTypeFor(AnnotatedQueryMethod meta) {
        return meta.isEntityElement(entityType) ? entityType : meta.elementType();
    }

    // ---------------------------------------------------------------------------------------------
    // native
    // ---------------------------------------------------------------------------------------------

    private Object executeNative(AnnotatedQueryMethod meta, Object[] args) {
        if (dialect == null) {
            return failMissingDialect(meta);
        }
        if (!meta.modifying() && !meta.isEntityElement(entityType)) {
            AnnotatedQueryException error = new AnnotatedQueryException(
                    "native @Query scalar/projection results are not supported in v1; "
                            + "return the entity type or use a JPQL @Query.");
            return meta.shape() == AnnotatedQueryMethod.Shape.FLUX ? Flux.error(error) : Mono.error(error);
        }
        if (meta.modifying()) {
            return adaptModifying(Mono.defer(() -> {
                NativeSqlTranslator.Translated translated = translateNative(meta, args);
                return operations.executeNative(new NativeQuery(translated.sql(), translated.bindings()));
            }), meta.modifyingResultType());
        }
        // 엔티티 반환 native SELECT: CompiledQuery로 감싸 core 엔티티 하이드레이션 경로에 위임.
        return switch (meta.shape()) {
            case MONO_SINGLE -> Mono.defer(() -> nativeEntityFlux(meta, args).next());
            default -> Flux.defer(() -> nativeEntityFlux(meta, args));
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Flux<Object> nativeEntityFlux(AnnotatedQueryMethod meta, Object[] args) {
        NativeSqlTranslator.Translated translated = translateNative(meta, args);
        RawCompiledQuery compiled = new RawCompiledQuery(translated.sql(), translated.bindings().size());
        return (Flux<Object>) operations.findAll((Class) entityType, compiled, translated.bindings().toArray());
    }

    private NativeSqlTranslator.Translated translateNative(AnnotatedQueryMethod meta, Object[] args) {
        Map<String, Object> named = new HashMap<>();
        Map<Integer, Object> positional = new HashMap<>();
        for (AnnotatedQueryMethod.Bindable bindable : meta.bindables()) {
            Object value = args[bindable.argIndex()];
            positional.put(bindable.positional(), value);
            if (bindable.name() != null) {
                named.put(bindable.name(), value);
            }
        }
        return NativeSqlTranslator.translate(meta.query(), named, positional, dialect.bindMarkers());
    }

    // ---------------------------------------------------------------------------------------------
    // Pageable / shared helpers
    // ---------------------------------------------------------------------------------------------

    private Pageable novaPageable(AnnotatedQueryMethod meta, Object[] args) {
        Object raw = args[meta.pageableArgIndex()];
        if (meta.pageableSpring()) {
            if (SpringDataQuerySupport.pageableHasSort(raw)) {
                throw new AnnotatedQueryException(
                        "@Query does not apply Pageable.getSort(); express ordering with ORDER BY in the query string.");
            }
            Pageable nova = SpringDataQuerySupport.toNovaPageable(raw);
            if (nova == null) {
                throw new AnnotatedQueryException(
                        "@Query paging requires a paged Pageable; Pageable.unpaged() is not supported.");
            }
            return nova;
        }
        return (Pageable) raw;
    }

    private Object adaptModifying(Mono<Long> affected, Class<?> resultType) {
        if (resultType == Integer.class) {
            return affected.map(Long::intValue);
        }
        if (resultType == Void.class) {
            return affected.then();
        }
        return affected;
    }

    private Object failMissingJpql(AnnotatedQueryMethod meta) {
        AnnotatedQueryException error = new AnnotatedQueryException(
                "JPQL @Query requires a JpqlExecutor; configure a Dialect and EntityMetadataFactory on the "
                        + "repository (NovaRepositoryFactoryBean) or pass a JpqlExecutor to SimpleReactiveRepository.");
        return errorFor(meta.shape(), error);
    }

    private Object failMissingDialect(AnnotatedQueryMethod meta) {
        AnnotatedQueryException error = new AnnotatedQueryException(
                "native @Query requires a Dialect for bind-marker rendering; configure one on the repository.");
        return errorFor(meta.shape(), error);
    }

    private Object errorFor(AnnotatedQueryMethod.Shape shape, Throwable error) {
        return shape == AnnotatedQueryMethod.Shape.FLUX ? Flux.error(error) : Mono.error(error);
    }
}
