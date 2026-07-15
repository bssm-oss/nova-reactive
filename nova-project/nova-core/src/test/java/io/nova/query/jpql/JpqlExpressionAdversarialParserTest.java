package io.nova.query.jpql;

import io.nova.query.jpql.ast.ComparisonOp;
import io.nova.query.jpql.ast.Expression;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.query.jpql.ast.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 독립 adversarial 파서 테스트(저자 테스트와 별개). Track B(ANY/ALL/SOME, JPA 3.1 함수, TRIM/EXTRACT,
 * LEFT/RIGHT 문맥 분기, KEY/VALUE/ENTRY/INDEX 거부)의 경계/충돌 케이스를 공격한다.
 */
class JpqlExpressionAdversarialParserTest {

    private static JpqlStatement.Select parseSelect(String jpql) {
        return assertInstanceOf(JpqlStatement.Select.class, new JpqlParser(jpql).parse());
    }

    // ---- Edge 1: 함수형 LEFT/RIGHT 와 조인형 LEFT JOIN 공존 -------------------------------

    @Test
    void functionLeftCoexistsWithLeftJoinAndSecondJoin() {
        JpqlStatement.Select select = parseSelect(
                "SELECT LEFT(e.name, 2) FROM Employee e LEFT JOIN e.department d INNER JOIN d.company c");

        assertInstanceOf(Expression.FunctionCall.class, select.selectItems().get(0).expression());
        assertEquals("LEFT", ((Expression.FunctionCall) select.selectItems().get(0).expression()).name());
        assertEquals(2, select.joins().size());
        assertFalse(select.joins().get(0).inner(), "first join should be LEFT (outer)");
        assertEquals("department", select.joins().get(0).relation());
        assertTrue(select.joins().get(1).inner(), "second join should be INNER");
        assertEquals("company", select.joins().get(1).relation());
    }

    @Test
    void functionRightInSelectAndLeftJoinInFromCoexist() {
        JpqlStatement.Select select = parseSelect(
                "SELECT RIGHT(e.name, 2) FROM Employee e LEFT JOIN e.department d");
        assertEquals("RIGHT", ((Expression.FunctionCall) select.selectItems().get(0).expression()).name());
        assertEquals(1, select.joins().size());
        assertFalse(select.joins().get(0).inner());
    }

    @Test
    void nestedLeftOfRightParses() {
        Expression.FunctionCall outer = (Expression.FunctionCall) parseSelect(
                "SELECT LEFT(RIGHT(e.name, 3), 2) FROM Employee e").selectItems().get(0).expression();
        assertEquals("LEFT", outer.name());
        Expression.FunctionCall inner = assertInstanceOf(Expression.FunctionCall.class, outer.arguments().get(0));
        assertEquals("RIGHT", inner.name());
    }

    @Test
    void functionLeftUsableInWherePredicate() {
        Predicate where = parseSelect(
                "SELECT e.id FROM Employee e WHERE LEFT(e.name, 2) = 'Ad'").where();
        Predicate.Comparison c = assertInstanceOf(Predicate.Comparison.class, where);
        assertEquals("LEFT", ((Expression.FunctionCall) c.left()).name());
    }

    @Test
    void bareLeftKeywordWithoutParenIsRejectedWithHelpfulMessage() {
        // LEFT/RIGHT는 뒤에 '('가 없으면 JOIN 예약어다. 식 위치에서 인자 없이 쓰면 명확히 거부되어야 한다.
        JpqlSyntaxException ex = assertThrows(JpqlSyntaxException.class,
                () -> parseSelect("SELECT LEFT FROM Employee e"));
        assertTrue(ex.getMessage().contains("LEFT"), ex.getMessage());
    }

    @Test
    void rightJoinIsNotSilentlyAccepted() {
        // JPA는 RIGHT JOIN을 정의하지 않는다. parseJoins가 RIGHT를 소비하지 않으므로 trailing-input으로 거부돼야 한다.
        assertThrows(JpqlSyntaxException.class,
                () -> parseSelect("SELECT e FROM Employee e RIGHT JOIN e.department d"));
    }

    // ---- Edge 2: ANY/ALL/SOME ---------------------------------------------------------------

    @Test
    void notEqualAllParsesAsAllQuantifier() {
        Predicate.Comparison c = assertInstanceOf(Predicate.Comparison.class, parseSelect(
                "SELECT e FROM Employee e WHERE e.salary <> ALL (SELECT m.salary FROM Employee m)").where());
        assertEquals(ComparisonOp.NE, c.op());
        Expression.QuantifiedSubquery q = assertInstanceOf(Expression.QuantifiedSubquery.class, c.right());
        assertEquals(Expression.Quantifier.ALL, q.quantifier());
    }

    @Test
    void lessThanSomeNormalizesToAny() {
        Predicate.Comparison c = assertInstanceOf(Predicate.Comparison.class, parseSelect(
                "SELECT e FROM Employee e WHERE e.salary < SOME (SELECT m.salary FROM Employee m)").where());
        Expression.QuantifiedSubquery q = assertInstanceOf(Expression.QuantifiedSubquery.class, c.right());
        assertEquals(Expression.Quantifier.ANY, q.quantifier());
    }

    // ---- Edge 3/4: EXTRACT / TRIM 문법 ------------------------------------------------------

    @Test
    void extractLowercaseFieldNormalizesToUpperAndKeepsFunctionSource() {
        Expression.Extract extract = assertInstanceOf(Expression.Extract.class, parseSelect(
                "SELECT EXTRACT(year FROM CURRENT_DATE) FROM Employee e").selectItems().get(0).expression());
        assertEquals("YEAR", extract.field());
        Expression.FunctionCall src = assertInstanceOf(Expression.FunctionCall.class, extract.source());
        assertEquals("CURRENT_DATE", src.name());
    }

    @Test
    void trimBothFromWithoutCharParses() {
        Expression.Trim trim = assertInstanceOf(Expression.Trim.class, parseSelect(
                "SELECT TRIM(BOTH FROM e.name) FROM Employee e").selectItems().get(0).expression());
        assertEquals("BOTH", trim.spec());
        assertNull(trim.trimChar());
        assertInstanceOf(Expression.Path.class, trim.value());
    }

    @Test
    void trimWithParameterTrimCharParses() {
        Expression.Trim trim = assertInstanceOf(Expression.Trim.class, parseSelect(
                "SELECT TRIM(LEADING :c FROM e.name) FROM Employee e").selectItems().get(0).expression());
        assertEquals("LEADING", trim.spec());
        assertInstanceOf(Expression.NamedParameter.class, trim.trimChar());
    }

    // ---- Edge 6: KEY/VALUE/ENTRY/INDEX 정밀 거부 -------------------------------------------

    @Test
    void keyValueEntryIndexEachRejectedWithPreciseMessage() {
        for (String fn : new String[] {"KEY", "VALUE", "ENTRY", "INDEX"}) {
            JpqlSyntaxException ex = assertThrows(JpqlSyntaxException.class,
                    () -> new JpqlParser("SELECT " + fn + "(m) FROM Employee e").parse(),
                    fn + " should be rejected");
            assertTrue(ex.getMessage().contains("KEY/VALUE/ENTRY/INDEX"),
                    "message for " + fn + " was: " + ex.getMessage());
        }
    }
}
