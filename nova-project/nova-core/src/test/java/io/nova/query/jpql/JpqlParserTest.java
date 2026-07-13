package io.nova.query.jpql;

import io.nova.query.jpql.ast.AggregateOp;
import io.nova.query.jpql.ast.ComparisonOp;
import io.nova.query.jpql.ast.Expression;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.query.jpql.ast.Predicate;
import io.nova.query.jpql.ast.SelectItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpqlParserTest {

    private static JpqlStatement parse(String jpql) {
        return new JpqlParser(jpql).parse();
    }

    private static JpqlStatement.Select parseSelect(String jpql) {
        return assertInstanceOf(JpqlStatement.Select.class, parse(jpql));
    }

    @Test
    void parsesSimpleEntitySelect() {
        JpqlStatement.Select select = parseSelect("SELECT e FROM Employee e");
        assertFalse(select.distinct());
        assertEquals("Employee", select.rootEntity());
        assertEquals("e", select.rootAlias());
        assertEquals(1, select.selectItems().size());
        SelectItem item = select.selectItems().get(0);
        assertTrue(item.isEntity());
        assertEquals("e", item.entityAlias());
        assertNull(select.where());
        assertTrue(select.joins().isEmpty());
    }

    @Test
    void parsesDistinctAndPathSelection() {
        JpqlStatement.Select select = parseSelect("SELECT DISTINCT e.name FROM Employee e");
        assertTrue(select.distinct());
        SelectItem item = select.selectItems().get(0);
        assertFalse(item.isEntity());
        Expression.Path path = assertInstanceOf(Expression.Path.class, item.expression());
        assertEquals("e", path.alias());
        assertEquals(1, path.segments().size());
        assertEquals("name", path.segments().get(0));
    }

    @Test
    void parsesComparisonWithNamedParameter() {
        JpqlStatement.Select select = parseSelect("SELECT e FROM Employee e WHERE e.salary >= :min");
        Predicate.Comparison c = assertInstanceOf(Predicate.Comparison.class, select.where());
        assertEquals(ComparisonOp.GE, c.op());
        assertInstanceOf(Expression.Path.class, c.left());
        Expression.NamedParameter p = assertInstanceOf(Expression.NamedParameter.class, c.right());
        assertEquals("min", p.name());
    }

    @Test
    void parsesPositionalParameterAndAndOr() {
        JpqlStatement.Select select = parseSelect(
                "SELECT e FROM Employee e WHERE e.name = ?1 AND (e.active = TRUE OR e.age < 30)");
        Predicate.And and = assertInstanceOf(Predicate.And.class, select.where());
        Predicate.Comparison left = assertInstanceOf(Predicate.Comparison.class, and.left());
        assertEquals(1, assertInstanceOf(Expression.PositionalParameter.class, left.right()).position());
        assertInstanceOf(Predicate.Or.class, and.right());
    }

    @Test
    void parsesLikeInBetweenIsNull() {
        Predicate like = parseSelect("SELECT e FROM Employee e WHERE e.name LIKE :p").where();
        assertFalse(assertInstanceOf(Predicate.Like.class, like).negated());

        Predicate notLike = parseSelect("SELECT e FROM Employee e WHERE e.name NOT LIKE :p").where();
        assertTrue(assertInstanceOf(Predicate.Like.class, notLike).negated());

        Predicate in = parseSelect("SELECT e FROM Employee e WHERE e.id IN (1, 2, 3)").where();
        assertEquals(3, assertInstanceOf(Predicate.InList.class, in).items().size());

        Predicate between = parseSelect("SELECT e FROM Employee e WHERE e.age BETWEEN 20 AND 30").where();
        assertInstanceOf(Predicate.Between.class, between);

        Predicate isNull = parseSelect("SELECT e FROM Employee e WHERE e.name IS NOT NULL").where();
        assertTrue(assertInstanceOf(Predicate.Null.class, isNull).negated());
    }

    @Test
    void parsesNumericLiteralsAsLongAndBigDecimal() {
        Predicate.Comparison intCmp = assertInstanceOf(Predicate.Comparison.class,
                parseSelect("SELECT e FROM Employee e WHERE e.age = 42").where());
        assertEquals(42L, assertInstanceOf(Expression.Literal.class, intCmp.right()).value());

        Predicate.Comparison decCmp = assertInstanceOf(Predicate.Comparison.class,
                parseSelect("SELECT e FROM Employee e WHERE e.salary = 1234.50").where());
        assertEquals(new BigDecimal("1234.50"),
                assertInstanceOf(Expression.Literal.class, decCmp.right()).value());
    }

    @Test
    void parsesAggregateGroupByHaving() {
        JpqlStatement.Select select = parseSelect(
                "SELECT d.name, COUNT(e) FROM Employee e JOIN e.department d GROUP BY d.name HAVING COUNT(e) > 5");
        assertEquals(2, select.selectItems().size());
        Expression.Aggregate agg =
                assertInstanceOf(Expression.Aggregate.class, select.selectItems().get(1).expression());
        assertEquals(AggregateOp.COUNT, agg.op());
        assertEquals(1, select.joins().size());
        assertEquals("department", select.joins().get(0).relation());
        assertEquals(1, select.groupBy().size());
        assertNotNull(select.having());
    }

    @Test
    void parsesCountStarAndDistinct() {
        Expression.Aggregate star = assertInstanceOf(Expression.Aggregate.class,
                parseSelect("SELECT COUNT(*) FROM Employee e").selectItems().get(0).expression());
        assertEquals(AggregateOp.COUNT, star.op());
        assertNull(star.argument());

        Expression.Aggregate distinct = assertInstanceOf(Expression.Aggregate.class,
                parseSelect("SELECT COUNT(DISTINCT e.department) FROM Employee e").selectItems().get(0).expression());
        assertTrue(distinct.distinct());
    }

    @Test
    void parsesFunctionsAndArithmeticAndCase() {
        JpqlStatement.Select fn = parseSelect("SELECT LOWER(e.name) FROM Employee e");
        assertInstanceOf(Expression.FunctionCall.class, fn.selectItems().get(0).expression());

        JpqlStatement.Select arith = parseSelect("SELECT e.salary * 2 FROM Employee e");
        assertInstanceOf(Expression.Arithmetic.class, arith.selectItems().get(0).expression());

        JpqlStatement.Select caseExpr = parseSelect(
                "SELECT CASE WHEN e.salary > 100 THEN 1 ELSE 0 END FROM Employee e");
        Expression.Case c = assertInstanceOf(Expression.Case.class, caseExpr.selectItems().get(0).expression());
        assertEquals(1, c.whens().size());
        assertNotNull(c.elseResult());
    }

    @Test
    void parsesExistsAndInSubquery() {
        Predicate exists = parseSelect(
                "SELECT e FROM Employee e WHERE EXISTS (SELECT 1 FROM Employee m WHERE m.id = e.id)").where();
        assertFalse(assertInstanceOf(Predicate.Exists.class, exists).negated());

        Predicate inSub = parseSelect(
                "SELECT e FROM Employee e WHERE e.id IN (SELECT m.id FROM Employee m WHERE m.age > 40)").where();
        assertInstanceOf(Predicate.InSubquery.class, inSub);
    }

    @Test
    void parsesOrderByAscDesc() {
        JpqlStatement.Select select = parseSelect("SELECT e FROM Employee e ORDER BY e.name ASC, e.age DESC");
        assertEquals(2, select.orderBy().size());
        assertTrue(select.orderBy().get(0).ascending());
        assertFalse(select.orderBy().get(1).ascending());
    }

    @Test
    void parsesBulkUpdate() {
        JpqlStatement.Update update = assertInstanceOf(JpqlStatement.Update.class,
                parse("UPDATE Employee e SET e.name = :n, e.age = e.age + 1 WHERE e.id = :id"));
        assertEquals("Employee", update.rootEntity());
        assertEquals(2, update.assignments().size());
        assertNotNull(update.where());
    }

    @Test
    void parsesBulkDelete() {
        JpqlStatement.Delete delete = assertInstanceOf(JpqlStatement.Delete.class,
                parse("DELETE FROM Employee e WHERE e.age < :limit"));
        assertEquals("Employee", delete.rootEntity());
        assertEquals("e", delete.rootAlias());
        assertNotNull(delete.where());
    }

    // ------------------------------------------------------------------------------------
    // Fail-fast rejections
    // ------------------------------------------------------------------------------------

    @Test
    void parsesJoinFetchWithAlias() {
        JpqlStatement.Select select = parseSelect("SELECT e FROM Employee e JOIN FETCH e.department d");
        assertEquals(1, select.joins().size());
        assertTrue(select.joins().get(0).fetch());
        assertEquals("e", select.joins().get(0).ownerAlias());
        assertEquals("department", select.joins().get(0).relation());
        assertEquals("d", select.joins().get(0).alias());
    }

    @Test
    void parsesJoinFetchWithoutAlias() {
        JpqlStatement.Select select = parseSelect("SELECT a FROM Author a JOIN FETCH a.books");
        assertEquals(1, select.joins().size());
        assertTrue(select.joins().get(0).fetch());
        assertEquals("books", select.joins().get(0).relation());
        assertNull(select.joins().get(0).alias());
    }

    @Test
    void parsesLeftJoinFetch() {
        JpqlStatement.Select select = parseSelect("SELECT a FROM Author a LEFT JOIN FETCH a.books b");
        assertEquals(1, select.joins().size());
        assertTrue(select.joins().get(0).fetch());
        assertFalse(select.joins().get(0).inner());
    }

    @Test
    void rejectsMultiLevelJoinFetch() {
        JpqlSyntaxException ex = assertThrows(JpqlSyntaxException.class,
                () -> parse("SELECT a FROM Author a JOIN FETCH a.books.reviews r"));
        assertTrue(ex.getMessage().contains("Multi-level"));
    }

    @Test
    void rejectsTreatAndType() {
        assertThrows(JpqlSyntaxException.class,
                () -> parse("SELECT e FROM Employee e WHERE TYPE(e) = Manager"));
        assertThrows(JpqlSyntaxException.class,
                () -> parse("SELECT TREAT(e AS Manager) FROM Employee e"));
    }

    @Test
    void parsesConstructorExpression() {
        JpqlStatement.Select select = parseSelect(
                "SELECT NEW com.x.Dto(e.name, e.age, COUNT(e)) FROM Employee e");
        assertEquals(1, select.selectItems().size());
        SelectItem item = select.selectItems().get(0);
        assertTrue(item.isConstructor());
        assertEquals("com.x.Dto", item.constructorCall().className());
        assertEquals(3, item.constructorCall().arguments().size());
        assertInstanceOf(Expression.Path.class, item.constructorCall().arguments().get(0));
        assertInstanceOf(Expression.Aggregate.class, item.constructorCall().arguments().get(2));
    }

    @Test
    void parsesMultiSegmentPathExpression() {
        JpqlStatement.Select select = parseSelect("SELECT e.department.name FROM Employee e");
        Expression.Path path = assertInstanceOf(Expression.Path.class,
                select.selectItems().get(0).expression());
        assertEquals("e", path.alias());
        assertEquals(2, path.segments().size());
        assertEquals("department", path.segments().get(0));
        assertEquals("name", path.segments().get(1));
    }

    @Test
    void parsesCastExpression() {
        JpqlStatement.Select select = parseSelect("SELECT CAST(e.age AS string) FROM Employee e");
        Expression.Cast cast = assertInstanceOf(Expression.Cast.class,
                select.selectItems().get(0).expression());
        assertEquals("STRING", cast.targetType());
        assertInstanceOf(Expression.Path.class, cast.value());
    }

    @Test
    void parsesLocateFunctionAndNativeFunction() {
        Expression.FunctionCall locate = assertInstanceOf(Expression.FunctionCall.class,
                parseSelect("SELECT LOCATE('a', e.name) FROM Employee e").selectItems().get(0).expression());
        assertEquals("LOCATE", locate.name());
        assertEquals(2, locate.arguments().size());

        Expression.FunctionCall fn = assertInstanceOf(Expression.FunctionCall.class,
                parseSelect("SELECT FUNCTION('date_trunc', 'month', e.name) FROM Employee e")
                        .selectItems().get(0).expression());
        assertEquals("FUNCTION", fn.name());
        assertEquals(3, fn.arguments().size());
    }

    @Test
    void rejectsSimpleCaseForm() {
        assertThrows(JpqlSyntaxException.class,
                () -> parse("SELECT CASE e.status WHEN 1 THEN 'a' ELSE 'b' END FROM Employee e"));
    }

    @Test
    void rejectsTrailingGarbageAndEmptyInput() {
        assertThrows(JpqlSyntaxException.class, () -> parse("SELECT e FROM Employee e EXTRA"));
        assertThrows(JpqlSyntaxException.class, () -> parse(""));
        assertThrows(JpqlSyntaxException.class, () -> parse("   "));
    }

    @Test
    void rejectsUnterminatedString() {
        assertThrows(JpqlSyntaxException.class, () -> new JpqlLexer("SELECT e FROM Employee e WHERE e.name = 'x").tokenize());
    }

    @Test
    void rejectsMemberOfAndIsEmpty() {
        assertThrows(JpqlSyntaxException.class,
                () -> parse("SELECT e FROM Employee e WHERE :x MEMBER OF e.roles"));
        assertThrows(JpqlSyntaxException.class,
                () -> parse("SELECT e FROM Employee e WHERE e.roles IS EMPTY"));
    }
}
