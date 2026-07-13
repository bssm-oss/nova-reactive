package io.nova.query.criteria;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.sql.Dialect;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;

import java.util.Objects;

/**
 * JPA Criteria API의 리액티브 실행 진입점. 표준 {@code jakarta.persistence.criteria.CriteriaBuilder}로
 * 조립한 {@link CriteriaQuery}를 blocking {@code EntityManager} 없이 {@code Flux}/{@code Mono}로 실행한다.
 * <p>
 * 기존 엔진의 hub 파일을 재작성하지 않고, 실행은 전적으로 {@link ReactiveEntityOperations}의 public 진입점
 * ({@code findAll}/{@code queryNative})에 위임한다. dialect와 metadata factory는 {@code operations}가 쓰는
 * 것과 동일해야 한다(bind marker/quoting/컬럼 매핑 일관성).
 *
 * <pre>{@code
 * ReactiveCriteriaExecutor criteria = new ReactiveCriteriaExecutor(operations, dialect, metadataFactory);
 * CriteriaBuilder cb = criteria.getCriteriaBuilder();
 * CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
 * Root<Employee> e = cq.from(Employee.class);
 * cq.select(e).where(cb.ge(e.get("salary"), 100)).orderBy(cb.asc(e.get("name")));
 * Flux<Employee> results = criteria.createQuery(cq).getResultList();
 * }</pre>
 */
public final class ReactiveCriteriaExecutor {

    private final ReactiveEntityOperations operations;
    private final CriteriaMetamodel metamodel;
    private final CriteriaSqlBuilder sqlBuilder;

    /**
     * @param operations      기존 리액티브 엔티티 오퍼레이션(위임 대상)
     * @param dialect         bind marker/식별자 quoting 제공 dialect(= operations가 쓰는 것과 동일해야 함)
     * @param metadataFactory 엔티티 메타데이터 팩토리(= operations가 쓰는 것과 동일해야 함)
     */
    public ReactiveCriteriaExecutor(
            ReactiveEntityOperations operations,
            Dialect dialect,
            EntityMetadataFactory metadataFactory) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");
        this.metamodel = new CriteriaMetamodel(metadataFactory);
        this.sqlBuilder = new CriteriaSqlBuilder(dialect);
    }

    /**
     * 이 실행기에 결속된 새 {@link CriteriaBuilder}를 만든다. 여기서 만든 {@link CriteriaQuery}만
     * {@link #createQuery(CriteriaQuery)}로 실행할 수 있다.
     */
    public CriteriaBuilder getCriteriaBuilder() {
        return new SimpleCriteriaBuilder(metamodel);
    }

    /**
     * 조립된 {@link CriteriaQuery}를 리액티브 실행 핸들로 감싼다. 이 실행기의 {@link CriteriaBuilder}가
     * 만들지 않은 쿼리는 fail-fast로 거부한다.
     */
    public <T> ReactiveCriteriaQuery<T> createQuery(CriteriaQuery<T> query) {
        Objects.requireNonNull(query, "query must not be null");
        if (!(query instanceof CriteriaQueryImpl<T> impl)) {
            throw new CriteriaException(
                    "CriteriaQuery was not created by this executor's CriteriaBuilder; "
                            + "use getCriteriaBuilder().createQuery(...)");
        }
        return new ReactiveCriteriaQuery<>(impl, operations, sqlBuilder);
    }
}
