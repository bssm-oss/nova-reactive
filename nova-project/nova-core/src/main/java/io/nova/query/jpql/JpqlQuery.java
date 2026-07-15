package io.nova.query.jpql;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.ToOneForeignKeyColumn;
import io.nova.query.Criteria;
import io.nova.query.NativeQuery;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.query.jpql.ast.ConstructorCall;
import io.nova.query.jpql.ast.Expression;
import io.nova.query.jpql.ast.SelectItem;
import io.nova.query.jpql.ast.JpqlStatement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
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
    private Constructor<?> resolvedConstructor;

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
        if (entityPlanner.isJoinedEntitySelect(select) && joinedEntityMatches(select)) {
            return executeJoinedEntity(select);
        }
        // 스칼라/집계/DTO 투영 경로는 페이지 창을 v1에서 지원하지 않는다.
        if (firstResult != null || maxResults != null) {
            return Flux.error(new JpqlException(
                    "setFirstResult/setMaxResults is only supported for entity-returning SELECT queries in v1"));
        }
        TranslatedSql translated;
        Function<RowAccessor, T> mapper;
        try {
            translated = sqlBuilder.buildScalarSelect(select);
            int columns = translated.selectionCount();
            ConstructorCall ctor = constructorProjection(select);
            List<TranslatedSql.ResultSlot> slots = translated.slots();
            mapper = ctor != null
                    ? constructorMapper(ctor, columns)
                    : row -> (T) mapSlots(row, slots);
        } catch (RuntimeException e) {
            return Flux.error(e);
        }
        return operations.queryNative(toNativeQuery(translated), mapper);
    }

    /** {@code SELECT NEW ...} 단일 생성자 프로젝션이면 그 {@link ConstructorCall}, 아니면 {@code null}. */
    private static ConstructorCall constructorProjection(JpqlStatement.Select select) {
        List<SelectItem> items = select.selectItems();
        if (items.size() == 1 && items.get(0).isConstructor()) {
            return items.get(0).constructorCall();
        }
        return null;
    }

    /** {@code resultType}이 조인된 엔티티 반환의 루트 엔티티 타입과 호환되는지(Object면 자동 허용). */
    private boolean joinedEntityMatches(JpqlStatement.Select select) {
        if (resultType == Object.class) {
            return true;
        }
        return resultType.isAssignableFrom(entityPlanner.rootMetadata(select).entityType());
    }

    /**
     * 필터용 non-fetch JOIN이 있는 엔티티 반환 SELECT를 2단계로 실행한다. 먼저 조인/WHERE로 매칭되는 루트 id를
     * {@code DISTINCT} 스칼라 투영으로 뽑고, 그 id 집합을 {@code IN} 조건으로 기존 하이드레이션 경로에 위임한다.
     * 이렇게 하면 컨버터/연관 로딩 등 엔티티 하이드레이션을 그대로 재사용하면서 코어 오퍼레이션을 건드리지 않는다.
     * <p>
     * <b>혼합(mixed) JOIN FETCH + non-fetch JOIN 의미:</b> 한 쿼리에 {@code JOIN FETCH}와 필터용 non-fetch
     * JOIN이 함께 있으면 이 2단계 경로로 라우팅된다({@link JpqlEntityQueryPlanner#isJoinedEntitySelect}).
     * 이때 id 투영({@link #rootIdProjection})은 <em>non-fetch JOIN만</em> 유지하고 {@code JOIN FETCH} 절은
     * 제외한다 — Nova는 매핑 연관을 always-eager로 로드하므로 {@code JOIN FETCH}는 fetch plan을 바꾸지 않는
     * <b>no-op(명시적 의도 표현)</b>이며, 지정된 연관은 뒤이은 배치 hydration({@code findAll})이 어차피 로드한다.
     * 즉 {@code JOIN FETCH}를 id 투영에서 빼도 결과 엔티티 그래프는 동일하고 cartesian 중복도 없다
     * ({@link JpqlEntityQueryPlanner#validateFetchJoins}의 always-eager passthrough와 동일 정합).
     */
    @SuppressWarnings("unchecked")
    private Flux<T> executeJoinedEntity(JpqlStatement.Select select) {
        EntityMetadata<?> metadata = entityPlanner.rootMetadata(select);
        if (resultType != Object.class && !resultType.isAssignableFrom(metadata.entityType())) {
            return Flux.error(new JpqlException("Query returns entity " + metadata.entityType().getName()
                    + " which is not assignable to requested result type " + resultType.getName()));
        }
        if (metadata.hasCompositeId()) {
            return Flux.error(new JpqlException(
                    "Entity-returning JPQL with a filtering JOIN is not supported for composite-id entity "
                            + metadata.entityType().getSimpleName()));
        }
        if (maxResults != null && maxResults == 0) {
            return Flux.empty();
        }
        String idProperty = metadata.idProperty().propertyName();

        TranslatedSql idProjection;
        Sort sort;
        Pageable page;
        try {
            idProjection = sqlBuilder.buildScalarSelect(rootIdProjection(select, idProperty));
            sort = entityPlanner.translateRootOrderBy(select.orderBy(), select.rootAlias(), metadata);
            page = pageWindow();
        } catch (RuntimeException e) {
            return Flux.error(e);
        }

        Class<Object> entityType = (Class<Object>) metadata.entityType();
        Function<RowAccessor, Object> idMapper = row -> row.get(JpqlQuery.columnLabel(0), Object.class);
        return operations.queryNative(toNativeQuery(idProjection), idMapper)
                .collectList()
                .flatMapMany(ids -> {
                    if (ids.isEmpty()) {
                        return Flux.<Object>empty();
                    }
                    QuerySpec spec = QuerySpec.empty().where(Criteria.in(idProperty, ids));
                    if (sort != null) {
                        spec = spec.orderBy(sort);
                    }
                    if (page != null) {
                        spec = spec.page(page);
                    }
                    return operations.findAll(entityType, spec);
                })
                .map(e -> (T) e);
    }

    /**
     * id 투영용 합성 SELECT: {@code SELECT DISTINCT root.id FROM ... [non-fetch joins] WHERE ...}.
     * <p>
     * {@code JOIN FETCH} 절은 의도적으로 제외한다 — always-eager 모델에서 fetch join은 fetch plan을 바꾸지
     * 않는 no-op이고, 지정된 연관은 뒤이은 hydration이 로드하므로 id 필터링에는 필터용 non-fetch JOIN만
     * 필요하다. fetch join을 id 투영에 넣으면(특히 to-many) cartesian 곱으로 id가 중복될 뿐이다.
     */
    private static JpqlStatement.Select rootIdProjection(JpqlStatement.Select select, String idProperty) {
        List<io.nova.query.jpql.ast.JoinClause> filterJoins = new ArrayList<>();
        for (io.nova.query.jpql.ast.JoinClause join : select.joins()) {
            if (!join.fetch()) {
                filterJoins.add(join);
            }
        }
        SelectItem idItem = SelectItem.of(
                new Expression.Path(select.rootAlias(), List.of(idProperty)), null);
        return new JpqlStatement.Select(
                true,
                List.of(idItem),
                select.rootEntity(),
                select.rootAlias(),
                filterJoins,
                select.where(),
                List.of(),
                null,
                List.of());
    }

    /** {@code firstResult}/{@code maxResults}를 {@link Pageable}로. 둘 다 없으면 {@code null}. */
    private Pageable pageWindow() {
        if (maxResults == null) {
            if (firstResult != null) {
                throw new JpqlException(
                        "setFirstResult without setMaxResults is not supported; provide a page size");
            }
            return null;
        }
        long offset = firstResult == null ? 0L : firstResult.longValue();
        return Pageable.of(maxResults.intValue(), offset);
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

    // ----------------------------------------------------------------------------------------
    // SELECT NEW ... DTO projection
    // ----------------------------------------------------------------------------------------

    private Function<RowAccessor, T> constructorMapper(ConstructorCall call, int columns) {
        Constructor<?> ctor = resolveConstructor(call, columns);
        Class<?>[] paramTypes = ctor.getParameterTypes();
        return row -> {
            Object[] args = new Object[columns];
            for (int i = 0; i < columns; i++) {
                Object raw = row.get(columnLabel(i), Object.class);
                args[i] = coerce(raw, paramTypes[i], call.className(), i);
            }
            try {
                @SuppressWarnings("unchecked")
                T instance = (T) ctor.newInstance(args);
                return instance;
            } catch (ReflectiveOperationException e) {
                throw new JpqlException("Failed to instantiate SELECT NEW target " + call.className()
                        + ": " + e.getMessage());
            }
        };
    }

    /** {@code className}의 public 생성자 중 인자 개수가 컬럼 수와 일치하는 하나를 리플렉션으로 찾는다(캐시). */
    private Constructor<?> resolveConstructor(ConstructorCall call, int columns) {
        if (resolvedConstructor != null) {
            return resolvedConstructor;
        }
        Class<?> dtoClass;
        try {
            dtoClass = Class.forName(call.className());
        } catch (ClassNotFoundException e) {
            throw new JpqlException("SELECT NEW target class not found: " + call.className());
        }
        Constructor<?> match = null;
        for (Constructor<?> candidate : dtoClass.getConstructors()) {
            if (candidate.getParameterCount() == columns) {
                if (match != null) {
                    throw new JpqlException("Ambiguous constructor: " + call.className() + " has more than one "
                            + "public constructor with " + columns + " parameters; SELECT NEW cannot disambiguate");
                }
                match = candidate;
            }
        }
        if (match == null) {
            throw new JpqlException("No public constructor of " + call.className() + " accepts " + columns
                    + " argument(s) for the SELECT NEW projection");
        }
        resolvedConstructor = match;
        return match;
    }

    /** 스칼라 컬럼 값을 생성자 파라미터 타입으로 강제 변환한다. 변환 불가면 fail-fast. */
    private static Object coerce(Object value, Class<?> target, String className, int index) {
        if (value == null) {
            if (target.isPrimitive()) {
                throw new JpqlException("SELECT NEW " + className + ": null cannot be assigned to primitive "
                        + "parameter " + index + " of type " + target.getName());
            }
            return null;
        }
        if (target.isInstance(value)) {
            return value;
        }
        if (value instanceof Number n) {
            if (target == int.class || target == Integer.class) {
                return n.intValue();
            }
            if (target == long.class || target == Long.class) {
                return n.longValue();
            }
            if (target == double.class || target == Double.class) {
                return n.doubleValue();
            }
            if (target == float.class || target == Float.class) {
                return n.floatValue();
            }
            if (target == short.class || target == Short.class) {
                return n.shortValue();
            }
            if (target == byte.class || target == Byte.class) {
                return n.byteValue();
            }
            if (target == BigDecimal.class) {
                return n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
            }
            if (target == BigInteger.class) {
                return n instanceof BigInteger bi ? bi : BigInteger.valueOf(n.longValue());
            }
        }
        if ((target == boolean.class || target == Boolean.class) && value instanceof Boolean b) {
            return b;
        }
        if (target == String.class) {
            return value.toString();
        }
        throw new JpqlException("SELECT NEW " + className + ": cannot convert value of type "
                + value.getClass().getName() + " to constructor parameter " + index + " of type "
                + target.getName());
    }

    /**
     * 논리 슬롯 목록을 기준으로 한 행을 매핑한다. 슬롯 1개면(기존 단일 scalar 동작 보존, {@code SELECT c.parent}
     * 단독 투영 포함) 그 슬롯 값을 그대로 반환하고, 그 외에는 슬롯별 값을 담은 {@code Object[]}를 반환한다.
     * 물리 selectionCount가 아니라 <b>논리 슬롯 개수</b>가 기준이다 — composite-only 투영은 물리 컬럼이 N>1개여도
     * 논리 슬롯은 1개이므로 bare stub을 반환한다.
     */
    private Object mapSlots(RowAccessor row, List<TranslatedSql.ResultSlot> slots) {
        if (slots.size() == 1) {
            return readSlot(row, slots.get(0));
        }
        Object[] values = new Object[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            values[i] = readSlot(row, slots.get(i));
        }
        return values;
    }

    /**
     * 슬롯 하나를 읽는다. 평범한 scalar 슬롯({@code compositeFk == null})은 라벨 컬럼 1개를 그대로 읽고,
     * 복합키 to-one 슬롯은 N개 FK 컬럼을 <b>저장 타입</b>으로 읽어(도메인 @Id 타입이 아니라 — read-source-type
     * 함정 회피, {@link ToOneForeignKeyColumn#columnType()}) 도메인 값으로 디코드한 뒤
     * {@link io.nova.metadata.ToOneForeignKey#assembleStub(List)}로 참조 엔티티 id-stub을 조립한다
     * (all-null이면 {@code null}).
     */
    private Object readSlot(RowAccessor row, TranslatedSql.ResultSlot slot) {
        if (slot.compositeFk() == null) {
            return row.get(columnLabel(slot.firstColumn()), Object.class);
        }
        List<ToOneForeignKeyColumn> fkColumns = slot.compositeFk().columns();
        List<Object> decoded = new ArrayList<>(fkColumns.size());
        for (int i = 0; i < fkColumns.size(); i++) {
            ToOneForeignKeyColumn fkColumn = fkColumns.get(i);
            Object stored = row.get(columnLabel(slot.firstColumn() + i), fkColumn.columnType());
            decoded.add(fkColumn.toPropertyValue(stored));
        }
        return slot.compositeFk().assembleStub(decoded);
    }

    private NativeQuery toNativeQuery(TranslatedSql translated) {
        List<Object> values = new ArrayList<>(translated.bindings().size());
        for (JpqlBinding binding : translated.bindings()) {
            values.add(parameters.resolve(binding));
        }
        return new NativeQuery(translated.sql(), values);
    }
}
