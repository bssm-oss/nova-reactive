package io.nova.query.jpql;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.sql.Dialect;

import java.util.List;
import java.util.Objects;

/**
 * JPQL 실행 진입점. 등록된 엔티티 클래스들에 대해 JPQL 문자열을 파싱→SQL 변환→리액티브 실행할 수 있는
 * {@link JpqlQuery}를 만든다.
 * <p>
 * 기존 엔진의 hub 파일을 재작성하지 않고, 실행은 전적으로 {@link ReactiveEntityOperations}의 public 진입점
 * ({@code findAll}/{@code queryNative}/{@code executeNative})에 위임한다. 신규 격리 서브시스템으로서
 * {@code io.nova.query.jpql} 패키지 밖의 코어 로직을 변경하지 않는다.
 */
public final class JpqlExecutor {

    private final ReactiveEntityOperations operations;
    private final JpqlEntityResolver resolver;
    private final JpqlSqlBuilder sqlBuilder;
    private final JpqlEntityQueryPlanner entityPlanner;

    /**
     * @param operations    기존 리액티브 엔티티 오퍼레이션(위임 대상)
     * @param dialect       bind marker/식별자 quoting 제공 dialect(= operations가 쓰는 것과 동일해야 함)
     * @param metadataFactory 엔티티 메타데이터 팩토리(= operations가 쓰는 것과 동일해야 함)
     * @param entityClasses JPQL에서 이름으로 참조할 수 있는 엔티티 클래스들
     */
    public JpqlExecutor(
            ReactiveEntityOperations operations,
            Dialect dialect,
            EntityMetadataFactory metadataFactory,
            Iterable<Class<?>> entityClasses) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");
        this.resolver = new JpqlEntityResolver(metadataFactory, entityClasses);
        this.sqlBuilder = new JpqlSqlBuilder(dialect, resolver);
        this.entityPlanner = new JpqlEntityQueryPlanner(resolver);
    }

    public JpqlExecutor(
            ReactiveEntityOperations operations,
            Dialect dialect,
            EntityMetadataFactory metadataFactory,
            Class<?>... entityClasses) {
        this(operations, dialect, metadataFactory, List.of(entityClasses));
    }

    /**
     * JPQL을 파싱해 결과 타입이 지정되지 않은 쿼리를 만든다. 스칼라 결과는 단일 컬럼이면 그 값, 여러 컬럼이면
     * {@code Object[]}로 발행된다.
     */
    public JpqlQuery<Object> createQuery(String jpql) {
        return createQuery(jpql, Object.class);
    }

    /**
     * JPQL을 파싱해 {@code resultType}으로 결과를 발행하는 쿼리를 만든다. 엔티티 반환 SELECT는
     * {@code resultType}이 그 엔티티 타입이어야 하며, 스칼라 단일 컬럼 결과는 해당 타입으로 캐스팅된다.
     */
    public <T> JpqlQuery<T> createQuery(String jpql, Class<T> resultType) {
        Objects.requireNonNull(resultType, "resultType must not be null");
        JpqlStatement statement = new JpqlParser(jpql).parse();
        return new JpqlQuery<>(statement, resultType, operations, sqlBuilder, entityPlanner);
    }
}
