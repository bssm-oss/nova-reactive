package io.nova.query.jpql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.support.fixtures.FixtureEntities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔티티 반환(join 없음) JPQL 계획 경로 단위 테스트. 이 경로는 {@code AbstractSqlRenderer}에 위임하며 복합키
 * 타겟 to-one을 단일 대표 FK 컬럼({@code columnName()} = 첫 FK 컬럼)으로만 렌더하므로, WHERE/ORDER BY에서
 * 복합 to-one을 참조하면 나머지 컴포넌트를 조용히 누락한 SQL(첫 컬럼만 비교)이 된다. 그 silent wrong-row를
 * 막는 build-time guard가 실제로 loud하게 발화하는지, 그리고 비-복합 술어는 회귀 없이 통과하는지 검증한다.
 */
class JpqlEntityQueryPlannerTest {

    private final EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final JpqlEntityQueryPlanner planner = new JpqlEntityQueryPlanner(new JpqlEntityResolver(
            metadataFactory,
            List.of(FixtureEntities.CompositeJoinChild.class, FixtureEntities.CompositeJoinParent.class)));

    private void plan(String jpql) {
        JpqlStatement.Select select = (JpqlStatement.Select) new JpqlParser(jpql).parse();
        planner.plan(select, new JpqlParameters());
    }

    private void assertRejectedComposite(String jpql) {
        JpqlException ex = assertThrows(JpqlException.class, () -> plan(jpql));
        assertTrue(ex.getMessage().contains("Composite-key to-one"),
                "message should name composite-key to-one: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("entity-returning"),
                "message should mention entity-returning path: " + ex.getMessage());
    }

    @Test
    void rejectsCompositeToOneOrderingComparisonInWhere() {
        assertRejectedComposite("SELECT c FROM CompositeJoinChild c WHERE c.parent < :p");
    }

    @Test
    void rejectsCompositeToOneEqualityInWhere() {
        // 이 경로의 등치도 첫 FK 컬럼만 비교하는 silent wrong-row이므로 함께 거부한다.
        assertRejectedComposite("SELECT c FROM CompositeJoinChild c WHERE c.parent = :p");
    }

    @Test
    void rejectsCompositeToOneIsNullInWhere() {
        assertRejectedComposite("SELECT c FROM CompositeJoinChild c WHERE c.parent IS NULL");
    }

    @Test
    void rejectsCompositeToOneInListInWhere() {
        assertRejectedComposite("SELECT c FROM CompositeJoinChild c WHERE c.parent IN (:a, :b)");
    }

    @Test
    void rejectsCompositeToOneBetweenInWhere() {
        assertRejectedComposite("SELECT c FROM CompositeJoinChild c WHERE c.parent BETWEEN :lo AND :hi");
    }

    @Test
    void rejectsCompositeToOneInOrderBy() {
        assertRejectedComposite("SELECT c FROM CompositeJoinChild c ORDER BY c.parent");
    }

    @Test
    void allowsNonCompositeBasicPredicateWithoutRegression() {
        // 비-복합 basic 컬럼 술어/정렬은 기존대로 통과해야 한다(guard가 과잉 거부하지 않음).
        assertDoesNotThrow(() ->
                plan("SELECT c FROM CompositeJoinChild c WHERE c.label = 'x' ORDER BY c.id"));
    }
}
