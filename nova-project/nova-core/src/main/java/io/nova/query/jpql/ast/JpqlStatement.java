package io.nova.query.jpql.ast;

import java.util.List;

/**
 * 파싱된 최상위 JPQL 문장. SELECT / bulk UPDATE / bulk DELETE 세 종류를 sealed로 표현한다.
 */
public sealed interface JpqlStatement
        permits JpqlStatement.Select, JpqlStatement.Update, JpqlStatement.Delete {

    /**
     * SELECT 문. {@code distinct}는 SELECT DISTINCT 여부. {@code selectItems}가 단일 엔티티 항목이고
     * 조인/집계/group이 없으면 실행 계층이 엔티티 조회 경로로, 아니면 스칼라/집계 경로로 라우팅한다.
     */
    record Select(
            boolean distinct,
            List<SelectItem> selectItems,
            String rootEntity,
            String rootAlias,
            List<JoinClause> joins,
            Predicate where,
            List<Expression.Path> groupBy,
            Predicate having,
            List<OrderItem> orderBy) implements JpqlStatement {
        public Select {
            selectItems = List.copyOf(selectItems);
            joins = List.copyOf(joins);
            groupBy = List.copyOf(groupBy);
            orderBy = List.copyOf(orderBy);
        }
    }

    /** 벌크 UPDATE. {@code UPDATE Entity [alias] SET a = x, b = y WHERE ...}. */
    record Update(
            String rootEntity,
            String rootAlias,
            List<Assignment> assignments,
            Predicate where) implements JpqlStatement {
        public Update {
            assignments = List.copyOf(assignments);
        }
    }

    /** 벌크 DELETE. {@code DELETE FROM Entity [alias] WHERE ...}. */
    record Delete(
            String rootEntity,
            String rootAlias,
            Predicate where) implements JpqlStatement {
    }
}
