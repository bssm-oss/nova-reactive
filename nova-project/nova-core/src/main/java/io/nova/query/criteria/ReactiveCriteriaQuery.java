package io.nova.query.criteria;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.metadata.EntityMetadata;
import io.nova.query.Criteria;
import io.nova.query.NativeQuery;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 조립된 {@link CriteriaQueryImpl}에 페이지 창을 바인딩해 리액티브로 실행하는 쿼리 핸들. JPA
 * {@code TypedQuery}의 리액티브 등가물로, {@code block()} 없이 {@link Flux}/{@link Mono}만 반환한다.
 * <p>
 * 선택 목록이 루트 엔티티(또는 기본값)이고 집계/GROUP BY가 없으면 {@link CriteriaEntityTranslator}로
 * {@link QuerySpec}을 만들어 기존 엔티티 하이드레이션 경로({@code findAll(Class, QuerySpec)})에 위임한다.
 * 그 외 스칼라/집계 SELECT는 {@link CriteriaSqlBuilder}가 만든 SQL을 {@code queryNative}로 실행한다.
 *
 * @param <T> 결과 원소 타입
 */
public final class ReactiveCriteriaQuery<T> {

    private final CriteriaQueryImpl<T> query;
    private final ReactiveEntityOperations operations;
    private final CriteriaSqlBuilder sqlBuilder;
    private final AliasedCriteriaSqlBuilder aliasedSqlBuilder;

    private Integer firstResult;
    private Integer maxResults;

    ReactiveCriteriaQuery(
            CriteriaQueryImpl<T> query,
            ReactiveEntityOperations operations,
            CriteriaSqlBuilder sqlBuilder,
            AliasedCriteriaSqlBuilder aliasedSqlBuilder) {
        this.query = query;
        this.operations = operations;
        this.sqlBuilder = sqlBuilder;
        this.aliasedSqlBuilder = aliasedSqlBuilder;
    }

    /** JPA {@code setFirstResult} 등가(0-기반 offset). 엔티티 반환 쿼리에서만 지원. */
    public ReactiveCriteriaQuery<T> setFirstResult(int firstResult) {
        if (firstResult < 0) {
            throw new CriteriaException("firstResult must be >= 0");
        }
        this.firstResult = firstResult;
        return this;
    }

    /** JPA {@code setMaxResults} 등가(limit). 엔티티 반환 쿼리에서만 지원. */
    public ReactiveCriteriaQuery<T> setMaxResults(int maxResults) {
        if (maxResults < 0) {
            throw new CriteriaException("maxResults must be >= 0");
        }
        this.maxResults = maxResults;
        return this;
    }

    /** SELECT 결과 목록을 발행한다. */
    public Flux<T> getResultList() {
        return Flux.defer(this::execute);
    }

    /**
     * 정확히 한 건의 결과를 발행한다. 결과가 없으면 에러(JPA {@code NoResultException} 등가), 두 건 이상이면
     * 에러(JPA {@code NonUniqueResultException} 등가)를 낸다.
     */
    public Mono<T> getSingleResult() {
        return getResultList().take(2).collectList().flatMap(list -> {
            if (list.isEmpty()) {
                return Mono.error(new CriteriaException("getSingleResult() found no rows"));
            }
            if (list.size() > 1) {
                return Mono.error(new CriteriaException("getSingleResult() found more than one row"));
            }
            return Mono.just(list.get(0));
        });
    }

    // --- execution ------------------------------------------------------------------------------

    private Flux<T> execute() {
        try {
            if (query.requiresAliasedSql()) {
                // join/서브쿼리를 포함하면 alias 한정 SQL 경로로 실행한다. 엔티티 반환은 루트 id를
                // 순서대로 투영한 뒤 기존 하이드레이션에 위임하는 2단계, 스칼라/집계는 native SQL이다.
                return isEntitySelect() ? executeEntityWithJoins() : executeScalarAliased();
            }
            if (isEntitySelect()) {
                return executeEntity();
            }
            return executeScalar();
        } catch (RuntimeException e) {
            return Flux.error(e);
        }
    }

    private boolean isEntitySelect() {
        if (!query.groupExpressions().isEmpty() || query.havingPredicate() != null) {
            return false;
        }
        List<jakarta.persistence.criteria.Selection<?>> selections = query.selections();
        if (selections.isEmpty()) {
            return true;
        }
        return selections.size() == 1 && selections.get(0) instanceof CriteriaRoot<?>;
    }

    @SuppressWarnings("unchecked")
    private Flux<T> executeEntity() {
        CriteriaRoot<?> root = query.root();
        EntityMetadata<?> metadata = root.ownerMetadata();
        if (query.isDistinct()) {
            // QuerySpec에는 DISTINCT 표현 수단이 없어 엔티티 경로에서 조용히 무시되므로 명시 플래그는 거부한다.
            return Flux.error(new CriteriaException(
                    "distinct(true) is not supported for entity-returning Criteria queries in v1; "
                            + "project scalar columns (multiselect) if you need SELECT DISTINCT"));
        }
        Class<T> resultType = query.getResultType();
        if (resultType != Object.class && !resultType.isAssignableFrom(metadata.entityType())) {
            return Flux.error(new CriteriaException("Query returns entity " + metadata.entityType().getName()
                    + " which is not assignable to requested result type " + resultType.getName()));
        }

        // cb.equal(root.type(), Subtype.class) 제한은 QuerySpec 술어가 아니라 조회 대상 타입을 서브타입으로
        // 좁혀 처리한다 — 코어 findAll(Subtype)이 discriminator 제한을 적용하므로 하이드레이션을 재사용한다.
        DiscriminatorNarrowing narrowing = narrowByDiscriminator(query.restriction(), metadata);

        QuerySpec spec = QuerySpec.empty();
        if (narrowing.remaining() != null) {
            spec = spec.where(CriteriaEntityTranslator.toPredicate(narrowing.remaining()));
        }
        if (!query.orders().isEmpty()) {
            Sort sort = CriteriaEntityTranslator.toSort(query.orders());
            spec = spec.orderBy(sort);
        }
        if (maxResults != null) {
            if (maxResults == 0) {
                return Flux.empty();
            }
            long offset = firstResult == null ? 0L : firstResult.longValue();
            spec = spec.page(Pageable.of(maxResults.intValue(), offset));
        } else if (firstResult != null) {
            return Flux.error(new CriteriaException(
                    "setFirstResult without setMaxResults is not supported; provide a page size"));
        }

        Class<Object> entityType = (Class<Object>) narrowing.target().entityType();
        return (Flux<T>) operations.findAll(entityType, spec);
    }

    /** 조회 대상 서브타입과 discriminator 제한을 제외한 나머지 술어. */
    private record DiscriminatorNarrowing(EntityMetadata<?> target, CriteriaPredicate remaining) {
    }

    /**
     * 최상위(또는 AND로 결합된) {@link DiscriminatorPredicate}를 뽑아 조회 대상 타입을 좁히고 나머지 술어를
     * 반환한다. discriminator 제한이 OR/NOT 등 좁힘 불가 위치에 있으면 fail-fast한다.
     */
    private static DiscriminatorNarrowing narrowByDiscriminator(CriteriaPredicate where, EntityMetadata<?> base) {
        if (where == null) {
            return new DiscriminatorNarrowing(base, null);
        }
        if (where instanceof DiscriminatorPredicate dp) {
            return new DiscriminatorNarrowing(dp.subtypeMetadata(), null);
        }
        if (where.kind() == CriteriaPredicate.Kind.AND) {
            List<CriteriaPredicate> others = new ArrayList<>();
            EntityMetadata<?> target = base;
            boolean narrowed = false;
            for (CriteriaPredicate child : where.children()) {
                if (child instanceof DiscriminatorPredicate dp) {
                    if (narrowed) {
                        throw new CriteriaException("Multiple cb.equal(root.type(), ...) restrictions in an "
                                + "entity-returning query are not supported; a row has exactly one concrete type");
                    }
                    target = dp.subtypeMetadata();
                    narrowed = true;
                } else if (containsDiscriminator(child)) {
                    throw new CriteriaException("cb.equal(root.type(), ...) is only supported at the top level "
                            + "(optionally AND-combined) of an entity-returning Criteria query");
                } else {
                    others.add(child);
                }
            }
            CriteriaPredicate remaining = others.isEmpty()
                    ? null
                    : (others.size() == 1 ? others.get(0)
                            : CriteriaPredicate.junction(CriteriaPredicate.Kind.AND, others));
            return new DiscriminatorNarrowing(target, remaining);
        }
        if (containsDiscriminator(where)) {
            throw new CriteriaException("cb.equal(root.type(), ...) is only supported at the top level "
                    + "(optionally AND-combined) of an entity-returning Criteria query");
        }
        return new DiscriminatorNarrowing(base, where);
    }

    private static boolean containsDiscriminator(CriteriaPredicate predicate) {
        if (predicate instanceof DiscriminatorPredicate) {
            return true;
        }
        return switch (predicate.kind()) {
            case AND, OR -> predicate.children().stream().anyMatch(ReactiveCriteriaQuery::containsDiscriminator);
            case NOT -> containsDiscriminator(predicate.inner());
            default -> false;
        };
    }

    private Flux<T> executeScalar() {
        if (firstResult != null || maxResults != null) {
            return Flux.error(new CriteriaException(
                    "setFirstResult/setMaxResults is only supported for entity-returning Criteria queries in v1"));
        }
        CriteriaSql translated = sqlBuilder.build(query);
        int columns = translated.selectionCount();
        Function<RowAccessor, T> mapper = row -> mapRow(row, columns);
        return operations.queryNative(new NativeQuery(translated.sql(), translated.bindings()), mapper);
    }

    /** join/서브쿼리를 포함한 스칼라/집계 SELECT: alias 한정 SQL을 native로 실행한다. */
    private Flux<T> executeScalarAliased() {
        if (firstResult != null || maxResults != null) {
            return Flux.error(new CriteriaException(
                    "setFirstResult/setMaxResults is only supported for entity-returning Criteria queries in v1"));
        }
        CriteriaSql translated = aliasedSqlBuilder.buildScalar(query);
        int columns = translated.selectionCount();
        Function<RowAccessor, T> mapper = row -> mapRow(row, columns);
        return operations.queryNative(new NativeQuery(translated.sql(), translated.bindings()), mapper);
    }

    /**
     * join/서브쿼리를 포함한 엔티티 반환 쿼리의 2단계 실행. (1) alias 한정 SQL로 루트 id를 순서대로
     * 투영해 중복 제거(to-many join의 카티전 중복 흡수)하고 페이지 창을 적용한 뒤, (2) 그 id들로 기존
     * 하이드레이션 경로({@code findAll(Class, IN 절)})에 위임해 연관까지 완전한 엔티티를 로드하고 1단계
     * 순서로 재배열한다. 항상-eager 모델과 정합한다.
     * <p>
     * 전제/한계: 루트는 단일 컬럼 {@code @Id}여야 한다(복합키 루트는 id 투영 시점에 거부됨). 중복 제거와
     * 재배열은 id 값의 {@code equals}/{@code hashCode}에 의존하므로 스칼라 단일 키를 가정한다. 또한
     * 2단계 {@code IN (id...)}은 한 문장에 매치된 루트 개수만큼 bind 파라미터를 싣는다 — 매우 큰 결과
     * (드라이버별 파라미터 한계, 예: PostgreSQL ~65535)에서는 {@code setMaxResults}로 페이지를 제한하는
     * 것을 권장한다(청크 분할은 v1 범위 밖).
     */
    @SuppressWarnings("unchecked")
    private Flux<T> executeEntityWithJoins() {
        CriteriaRoot<?> root = query.root();
        EntityMetadata<?> metadata = root.ownerMetadata();
        if (query.isDistinct()) {
            // 루트 id 투영이 이미 중복을 제거하므로 distinct(true)는 무의미하며, 조용한 무시 대신 명시 거부한다.
            return Flux.error(new CriteriaException(
                    "distinct(true) is redundant for entity-returning join queries (root ids are de-duplicated); "
                            + "remove distinct(true)"));
        }
        Class<T> resultType = query.getResultType();
        if (resultType != Object.class && !resultType.isAssignableFrom(metadata.entityType())) {
            return Flux.error(new CriteriaException("Query returns entity " + metadata.entityType().getName()
                    + " which is not assignable to requested result type " + resultType.getName()));
        }
        if (maxResults != null && maxResults == 0) {
            return Flux.empty();
        }
        if (maxResults == null && firstResult != null) {
            return Flux.error(new CriteriaException(
                    "setFirstResult without setMaxResults is not supported; provide a page size"));
        }

        CriteriaSql idSql = aliasedSqlBuilder.buildRootIdProjection(query);
        String idColumn = CriteriaSqlBuilder.columnLabel(0);
        Class<Object> entityType = (Class<Object>) metadata.entityType();
        String idPropertyName = metadata.idProperty().propertyName();

        return operations.queryNative(new NativeQuery(idSql.sql(), idSql.bindings()),
                        row -> row.get(idColumn, Object.class))
                .collectList()
                .flatMapMany(rawIds -> {
                    List<Object> ordered = window(dedupe(rawIds));
                    if (ordered.isEmpty()) {
                        return Flux.empty();
                    }
                    QuerySpec spec = QuerySpec.empty().where(Criteria.in(idPropertyName, ordered));
                    return operations.findAll(entityType, spec)
                            .collectList()
                            .flatMapMany(loaded -> Flux.fromIterable(reorder(loaded, ordered, metadata)));
                })
                .map(entity -> (T) entity);
    }

    /** 등장 순서를 보존하며 중복 id를 제거한다(to-many join으로 생긴 중복 행 흡수). */
    private static List<Object> dedupe(List<Object> ids) {
        List<Object> result = new ArrayList<>(ids.size());
        java.util.HashSet<Object> seen = new java.util.HashSet<>();
        for (Object id : ids) {
            if (id != null && seen.add(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /** setFirstResult/setMaxResults 페이지 창을 정렬된 id 목록에 적용한다. */
    private List<Object> window(List<Object> ids) {
        int from = firstResult == null ? 0 : firstResult;
        if (from >= ids.size()) {
            return List.of();
        }
        int to = maxResults == null ? ids.size() : Math.min(ids.size(), from + maxResults);
        return new ArrayList<>(ids.subList(from, to));
    }

    /** 하이드레이션 결과를 1단계 id 순서로 재배열한다(DB가 IN 절 순서를 보장하지 않으므로). */
    private static List<Object> reorder(List<Object> loaded, List<Object> orderedIds, EntityMetadata<?> metadata) {
        Map<Object, Object> byId = new LinkedHashMap<>();
        for (Object entity : loaded) {
            byId.put(metadata.idProperty().read(entity), entity);
        }
        List<Object> result = new ArrayList<>(orderedIds.size());
        for (Object id : orderedIds) {
            Object entity = byId.get(id);
            if (entity != null) {
                result.add(entity);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private T mapRow(RowAccessor row, int columns) {
        if (columns == 1) {
            return (T) row.get(CriteriaSqlBuilder.columnLabel(0), Object.class);
        }
        Object[] values = new Object[columns];
        for (int i = 0; i < columns; i++) {
            values[i] = row.get(CriteriaSqlBuilder.columnLabel(i), Object.class);
        }
        return (T) values;
    }
}
