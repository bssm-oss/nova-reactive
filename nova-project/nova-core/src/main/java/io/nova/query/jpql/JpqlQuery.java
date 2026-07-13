package io.nova.query.jpql;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.metadata.EntityMetadata;
import io.nova.query.NativeQuery;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.jpql.ast.JpqlStatement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 파싱된 JPQL 문장에 파라미터/페이지 창을 바인딩해 리액티브로 실행하는 쿼리 핸들. JPA {@code TypedQuery}의
 * 리액티브 등가물이다 — {@code block()} 없이 {@link Flux}/{@link Mono}만 반환한다.
 * <p>
 * SELECT 결과가 엔티티 자체이고 조인/집계가 없으면 {@link JpqlEntityQueryPlanner}가 만든 {@link QuerySpec}으로
 * 기존 엔티티 하이드레이션 경로에 위임한다. 그 외 스칼라/집계 SELECT는 {@link JpqlSqlBuilder}가 만든 SQL을
 * {@code queryNative}로, 벌크 UPDATE/DELETE는 {@code executeNative}로 실행한다.
 *
 * @param <T> 결과 원소 타입
 */
public final class JpqlQuery<T> {

    private final JpqlStatement statement;
    private final Class<T> resultType;
    private final ReactiveEntityOperations operations;
    private final JpqlSqlBuilder sqlBuilder;
    private final JpqlEntityQueryPlanner entityPlanner;
    private final JpqlParameters parameters = new JpqlParameters();

    private Integer firstResult;
    private Integer maxResults;

    JpqlQuery(
            JpqlStatement statement,
            Class<T> resultType,
            ReactiveEntityOperations operations,
            JpqlSqlBuilder sqlBuilder,
            JpqlEntityQueryPlanner entityPlanner) {
        this.statement = statement;
        this.resultType = resultType;
        this.operations = operations;
        this.sqlBuilder = sqlBuilder;
        this.entityPlanner = entityPlanner;
    }

    /** 스칼라 결과의 컬럼 라벨(0-기반). 안정적 라벨로 결과를 위치가 아닌 이름으로 읽는다. */
    static String columnLabel(int index) {
        return "c" + index;
    }

    public JpqlQuery<T> setParameter(String name, Object value) {
        parameters.setNamed(name, value);
        return this;
    }

    public JpqlQuery<T> setParameter(int position, Object value) {
        parameters.setPositional(position, value);
        return this;
    }

    /** JPA {@code setFirstResult} 등가(0-기반 offset). 엔티티 반환 SELECT에서만 지원. */
    public JpqlQuery<T> setFirstResult(int firstResult) {
        if (firstResult < 0) {
            throw new JpqlException("firstResult must be >= 0");
        }
        this.firstResult = firstResult;
        return this;
    }

    /** JPA {@code setMaxResults} 등가(limit). 엔티티 반환 SELECT에서만 지원. */
    public JpqlQuery<T> setMaxResults(int maxResults) {
        if (maxResults < 0) {
            throw new JpqlException("maxResults must be >= 0");
        }
        this.maxResults = maxResults;
        return this;
    }

    // ----------------------------------------------------------------------------------------
    // Execution
    // ----------------------------------------------------------------------------------------

    /** SELECT 결과 목록을 발행한다. UPDATE/DELETE 문에 호출하면 에러 신호. */
    public Flux<T> getResultList() {
        if (!(statement instanceof JpqlStatement.Select select)) {
            return Flux.error(new JpqlException("getResultList() requires a SELECT statement; "
                    + "use executeUpdate() for bulk UPDATE/DELETE"));
        }
        return Flux.defer(() -> execute(select));
    }

    /**
     * 정확히 한 건의 결과를 발행한다. 결과가 없으면 빈 {@link Mono}가 아니라 에러(JPA {@code NoResultException}
     * 등가)를, 두 건 이상이면 에러(JPA {@code NonUniqueResultException} 등가)를 낸다.
     */
    public Mono<T> getSingleResult() {
        return getResultList().take(2).collectList().flatMap(list -> {
            if (list.isEmpty()) {
                return Mono.error(new JpqlException("getSingleResult() found no rows"));
            }
            if (list.size() > 1) {
                return Mono.error(new JpqlException("getSingleResult() found more than one row"));
            }
            return Mono.just(list.get(0));
        });
    }

    /** 벌크 UPDATE/DELETE를 실행하고 영향 행 수를 발행한다. SELECT 문에 호출하면 에러 신호. */
    public Mono<Long> executeUpdate() {
        return Mono.defer(() -> {
            TranslatedSql translated;
            if (statement instanceof JpqlStatement.Update update) {
                translated = sqlBuilder.buildUpdate(update);
            } else if (statement instanceof JpqlStatement.Delete delete) {
                translated = sqlBuilder.buildDelete(delete);
            } else {
                return Mono.error(new JpqlException(
                        "executeUpdate() requires a bulk UPDATE/DELETE statement; use getResultList() for SELECT"));
            }
            return operations.executeNative(toNativeQuery(translated));
        });
    }

    // ----------------------------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Flux<T> execute(JpqlStatement.Select select) {
        if (entityPlanner.isEntitySelect(select) && !isForcedScalar()) {
            return executeEntity(select);
        }
        // 스칼라/집계 경로는 페이지 창을 v1에서 지원하지 않는다.
        if (firstResult != null || maxResults != null) {
            return Flux.error(new JpqlException(
                    "setFirstResult/setMaxResults is only supported for entity-returning SELECT queries in v1"));
        }
        TranslatedSql translated;
        try {
            translated = sqlBuilder.buildScalarSelect(select);
        } catch (RuntimeException e) {
            return Flux.error(e);
        }
        int columns = translated.selectionCount();
        Function<RowAccessor, T> mapper = row -> (T) mapScalarRow(row, columns);
        return operations.queryNative(toNativeQuery(translated), mapper);
    }

    /** {@code resultType}이 엔티티 타입이 아니면(스칼라 강제) 엔티티 경로를 우회한다. */
    private boolean isForcedScalar() {
        // resultType이 Object면 엔티티/스칼라 자동 판별에 맡긴다.
        return resultType != Object.class && !resolvedEntityMatches();
    }

    private boolean resolvedEntityMatches() {
        if (!(statement instanceof JpqlStatement.Select select) || !entityPlanner.isEntitySelect(select)) {
            return false;
        }
        // 파라미터/WHERE 해석 없이 루트 엔티티 타입만 보고 판별한다.
        EntityMetadata<?> metadata = entityPlanner.rootMetadata(select);
        return resultType.isAssignableFrom(metadata.entityType());
    }

    @SuppressWarnings("unchecked")
    private Flux<T> executeEntity(JpqlStatement.Select select) {
        JpqlEntityQueryPlanner.EntityPlan plan;
        try {
            plan = entityPlanner.plan(select, parameters);
        } catch (RuntimeException e) {
            return Flux.error(e);
        }
        EntityMetadata<?> metadata = plan.metadata();
        if (resultType != Object.class && !resultType.isAssignableFrom(metadata.entityType())) {
            return Flux.error(new JpqlException("Query returns entity " + metadata.entityType().getName()
                    + " which is not assignable to requested result type " + resultType.getName()));
        }
        QuerySpec spec = plan.spec();
        if (maxResults != null) {
            if (maxResults == 0) {
                return Flux.empty();
            }
            long offset = firstResult == null ? 0L : firstResult.longValue();
            spec = spec.page(Pageable.of(maxResults.intValue(), offset));
        } else if (firstResult != null) {
            return Flux.error(new JpqlException(
                    "setFirstResult without setMaxResults is not supported; provide a page size"));
        }
        Class<Object> entityType = (Class<Object>) metadata.entityType();
        return (Flux<T>) operations.findAll(entityType, spec);
    }

    private Object mapScalarRow(RowAccessor row, int columns) {
        if (columns == 1) {
            return row.get(columnLabel(0), Object.class);
        }
        Object[] values = new Object[columns];
        for (int i = 0; i < columns; i++) {
            values[i] = row.get(columnLabel(i), Object.class);
        }
        return values;
    }

    private NativeQuery toNativeQuery(TranslatedSql translated) {
        List<Object> values = new ArrayList<>(translated.bindings().size());
        for (JpqlBinding binding : translated.bindings()) {
            values.add(parameters.resolve(binding));
        }
        return new NativeQuery(translated.sql(), values);
    }
}
