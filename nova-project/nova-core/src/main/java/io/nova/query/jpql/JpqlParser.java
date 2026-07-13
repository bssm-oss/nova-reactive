package io.nova.query.jpql;

import io.nova.query.jpql.ast.AggregateOp;
import io.nova.query.jpql.ast.ArithmeticOp;
import io.nova.query.jpql.ast.Assignment;
import io.nova.query.jpql.ast.ComparisonOp;
import io.nova.query.jpql.ast.Expression;
import io.nova.query.jpql.ast.JoinClause;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.query.jpql.ast.OrderItem;
import io.nova.query.jpql.ast.Predicate;
import io.nova.query.jpql.ast.SelectItem;
import io.nova.query.jpql.ast.Subquery;
import io.nova.query.jpql.ast.WhenClause;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 손으로 작성한 재귀 하강 파서다. {@link JpqlLexer}가 만든 토큰 스트림을 {@link JpqlStatement} AST로
 * 변환한다. 지원 문법을 벗어나거나 v1에서 명시적으로 거부하는 구문(JOIN FETCH, TREAT, TYPE,
 * simple CASE, NEW 생성자, MEMBER OF 등)은 조용히 무시하지 않고 {@link JpqlSyntaxException}으로 fail-fast한다.
 */
public final class JpqlParser {

    private static final Set<String> AGGREGATE_NAMES = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");
    // 인자 없이 쓰는 시간 함수(JPQL은 괄호 없이 표기).
    private static final Set<String> NO_ARG_FUNCTIONS = Set.of("CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP");

    private final List<JpqlToken> tokens;
    private int index;

    public JpqlParser(String jpql) {
        this.tokens = new JpqlLexer(jpql).tokenize();
        this.index = 0;
    }

    /** 전체 JPQL 문장을 파싱한다. 파싱 후 남은 토큰이 있으면 fail-fast. */
    public JpqlStatement parse() {
        JpqlStatement statement;
        if (isKeyword("SELECT")) {
            statement = parseSelect();
        } else if (isKeyword("UPDATE")) {
            statement = parseUpdate();
        } else if (isKeyword("DELETE")) {
            statement = parseDelete();
        } else {
            throw syntax("Expected SELECT, UPDATE, or DELETE at start of JPQL");
        }
        expectEof();
        return statement;
    }

    // ----------------------------------------------------------------------------------------
    // SELECT
    // ----------------------------------------------------------------------------------------

    private JpqlStatement.Select parseSelect() {
        expectKeyword("SELECT");
        boolean distinct = consumeKeyword("DISTINCT");
        List<SelectItem> items = parseSelectItems();

        expectKeyword("FROM");
        String rootEntity = expectIdentifier("entity name");
        String rootAlias = parseAlias("root");
        List<JoinClause> joins = parseJoins();

        Predicate where = consumeKeyword("WHERE") ? parsePredicate() : null;
        List<Expression.Path> groupBy = parseGroupBy();
        Predicate having = consumeKeyword("HAVING") ? parsePredicate() : null;
        List<OrderItem> orderBy = parseOrderBy();

        return new JpqlStatement.Select(distinct, items, rootEntity, rootAlias, joins, where, groupBy, having, orderBy);
    }

    private List<SelectItem> parseSelectItems() {
        List<SelectItem> items = new ArrayList<>();
        items.add(parseSelectItem());
        while (consumeOperator(",")) {
            items.add(parseSelectItem());
        }
        return items;
    }

    private SelectItem parseSelectItem() {
        if (isKeyword("NEW")) {
            throw syntax("JPQL constructor expression (SELECT NEW ...) is not supported in v1");
        }
        Expression expr = parseExpression();
        String alias = null;
        if (consumeKeyword("AS")) {
            alias = expectIdentifier("select item alias");
        } else if (peek().type() == TokenType.IDENTIFIER) {
            alias = advance().text();
        }
        // 세그먼트 없는 순수 경로(별칭 하나)는 엔티티 자체 선택으로 취급한다: SELECT e / SELECT DISTINCT e
        if (alias == null && expr instanceof Expression.Path p && p.segments().isEmpty()) {
            return SelectItem.entity(p.alias());
        }
        return SelectItem.of(expr, alias);
    }

    private List<JoinClause> parseJoins() {
        List<JoinClause> joins = new ArrayList<>();
        while (true) {
            boolean inner;
            if (consumeKeyword("INNER")) {
                inner = true;
            } else if (consumeKeyword("LEFT")) {
                consumeKeyword("OUTER");
                inner = false;
            } else if (isKeyword("JOIN")) {
                inner = true;
            } else {
                break;
            }
            expectKeyword("JOIN");
            boolean fetch = consumeKeyword("FETCH");
            if (fetch) {
                throw syntax("JOIN FETCH is not supported in v1 (deferred to Wave2 W3); "
                        + "use an explicit fetch plan or a non-fetch join");
            }
            String ownerAlias = expectIdentifier("join path owner alias");
            expectOperator(".");
            String relation = expectIdentifier("join relation field");
            if (isOperator(".")) {
                throw syntax("Multi-level join path (" + ownerAlias + "." + relation
                        + ". ...) is not supported in v1");
            }
            String alias = parseAlias("join");
            if (alias == null) {
                throw syntax("JOIN " + ownerAlias + "." + relation + " requires an alias");
            }
            if (consumeKeyword("ON")) {
                throw syntax("JOIN ... ON custom condition is not supported in v1");
            }
            joins.add(new JoinClause(inner, false, ownerAlias, relation, alias));
        }
        return joins;
    }

    private List<Expression.Path> parseGroupBy() {
        if (!isKeyword("GROUP")) {
            return List.of();
        }
        expectKeyword("GROUP");
        expectKeyword("BY");
        List<Expression.Path> paths = new ArrayList<>();
        paths.add(parsePath());
        while (consumeOperator(",")) {
            paths.add(parsePath());
        }
        return paths;
    }

    private List<OrderItem> parseOrderBy() {
        if (!isKeyword("ORDER")) {
            return List.of();
        }
        expectKeyword("ORDER");
        expectKeyword("BY");
        List<OrderItem> items = new ArrayList<>();
        items.add(parseOrderItem());
        while (consumeOperator(",")) {
            items.add(parseOrderItem());
        }
        return items;
    }

    private OrderItem parseOrderItem() {
        Expression expr = parseExpression();
        boolean ascending = true;
        if (consumeKeyword("ASC")) {
            ascending = true;
        } else if (consumeKeyword("DESC")) {
            ascending = false;
        }
        return new OrderItem(expr, ascending);
    }

    // ----------------------------------------------------------------------------------------
    // UPDATE / DELETE
    // ----------------------------------------------------------------------------------------

    private JpqlStatement.Update parseUpdate() {
        expectKeyword("UPDATE");
        String rootEntity = expectIdentifier("entity name");
        String rootAlias = parseAlias("update root");
        expectKeyword("SET");
        List<Assignment> assignments = new ArrayList<>();
        assignments.add(parseAssignment());
        while (consumeOperator(",")) {
            assignments.add(parseAssignment());
        }
        Predicate where = consumeKeyword("WHERE") ? parsePredicate() : null;
        return new JpqlStatement.Update(rootEntity, rootAlias, assignments, where);
    }

    private Assignment parseAssignment() {
        Expression.Path target = parsePath();
        expectOperator("=");
        Expression value = parseExpression();
        return new Assignment(target, value);
    }

    private JpqlStatement.Delete parseDelete() {
        expectKeyword("DELETE");
        expectKeyword("FROM");
        String rootEntity = expectIdentifier("entity name");
        String rootAlias = parseAlias("delete root");
        Predicate where = consumeKeyword("WHERE") ? parsePredicate() : null;
        return new JpqlStatement.Delete(rootEntity, rootAlias, where);
    }

    // ----------------------------------------------------------------------------------------
    // Predicate
    // ----------------------------------------------------------------------------------------

    private Predicate parsePredicate() {
        return parseOr();
    }

    private Predicate parseOr() {
        Predicate left = parseAnd();
        while (consumeKeyword("OR")) {
            left = new Predicate.Or(left, parseAnd());
        }
        return left;
    }

    private Predicate parseAnd() {
        Predicate left = parseNot();
        while (consumeKeyword("AND")) {
            left = new Predicate.And(left, parseNot());
        }
        return left;
    }

    private Predicate parseNot() {
        if (consumeKeyword("NOT")) {
            return new Predicate.Not(parseNot());
        }
        return parsePrimaryPredicate();
    }

    private Predicate parsePrimaryPredicate() {
        // 괄호로 감싼 서브프레디킷: '(' 다음이 SELECT면 서브쿼리 표현식이므로 여기서 처리하지 않는다.
        if (isOperator("(") && !isKeywordAt(index + 1, "SELECT")) {
            expectOperator("(");
            Predicate inner = parsePredicate();
            expectOperator(")");
            return inner;
        }
        if (isKeyword("EXISTS")) {
            expectKeyword("EXISTS");
            expectOperator("(");
            Subquery sub = parseSubquery();
            expectOperator(")");
            return new Predicate.Exists(sub, false);
        }
        Expression left = parseExpression();
        return parseComparisonRest(left);
    }

    private Predicate parseComparisonRest(Expression left) {
        // 비교 연산자
        if (peek().type() == TokenType.OPERATOR && isComparisonSymbol(peek().text())) {
            String symbol = advance().text();
            // 스칼라 서브쿼리 비교: left op (subquery)
            Expression right = parseExpression();
            return new Predicate.Comparison(ComparisonOp.fromSymbol(symbol), left, right);
        }
        boolean negated = consumeKeyword("NOT");
        if (consumeKeyword("LIKE")) {
            Expression pattern = parseExpression();
            Character escape = null;
            if (consumeKeyword("ESCAPE")) {
                JpqlToken esc = expect(TokenType.STRING, "ESCAPE character string literal");
                if (esc.text().length() != 1) {
                    throw syntax("ESCAPE must be a single-character string literal");
                }
                escape = esc.text().charAt(0);
            }
            return new Predicate.Like(left, pattern, escape, negated);
        }
        if (consumeKeyword("BETWEEN")) {
            Expression low = parseAdditive();
            expectKeyword("AND");
            Expression high = parseAdditive();
            return new Predicate.Between(left, low, high, negated);
        }
        if (consumeKeyword("IN")) {
            expectOperator("(");
            if (isKeywordAt(index, "SELECT")) {
                Subquery sub = parseSubquery();
                expectOperator(")");
                return new Predicate.InSubquery(left, sub, negated);
            }
            List<Expression> items = new ArrayList<>();
            items.add(parseExpression());
            while (consumeOperator(",")) {
                items.add(parseExpression());
            }
            expectOperator(")");
            return new Predicate.InList(left, items, negated);
        }
        if (consumeKeyword("MEMBER")) {
            throw syntax("MEMBER OF collection predicate is not supported in v1");
        }
        if (negated) {
            throw syntax("Dangling NOT before " + describe(peek()) + "; expected LIKE, IN, or BETWEEN");
        }
        if (consumeKeyword("IS")) {
            boolean isNotNull = consumeKeyword("NOT");
            if (consumeKeyword("NULL")) {
                return new Predicate.Null(left, isNotNull);
            }
            if (consumeKeyword("EMPTY")) {
                throw syntax("IS [NOT] EMPTY collection predicate is not supported in v1");
            }
            throw syntax("Expected NULL after IS [NOT]");
        }
        throw syntax("Expected a comparison/LIKE/IN/BETWEEN/IS NULL predicate but found " + describe(peek()));
    }

    // ----------------------------------------------------------------------------------------
    // Expression
    // ----------------------------------------------------------------------------------------

    private Expression parseExpression() {
        return parseAdditive();
    }

    private Expression parseAdditive() {
        Expression left = parseMultiplicative();
        while (peek().type() == TokenType.OPERATOR && (peek().text().equals("+") || peek().text().equals("-"))) {
            ArithmeticOp op = advance().text().equals("+") ? ArithmeticOp.ADD : ArithmeticOp.SUBTRACT;
            left = new Expression.Arithmetic(op, left, parseMultiplicative());
        }
        return left;
    }

    private Expression parseMultiplicative() {
        Expression left = parseUnary();
        while (peek().type() == TokenType.OPERATOR && (peek().text().equals("*") || peek().text().equals("/"))) {
            ArithmeticOp op = advance().text().equals("*") ? ArithmeticOp.MULTIPLY : ArithmeticOp.DIVIDE;
            left = new Expression.Arithmetic(op, left, parseUnary());
        }
        return left;
    }

    private Expression parseUnary() {
        if (isOperator("-")) {
            advance();
            // 단항 마이너스는 0 - x 로 정규화
            return new Expression.Arithmetic(ArithmeticOp.SUBTRACT, new Expression.Literal(0L), parseUnary());
        }
        if (isOperator("+")) {
            advance();
            return parseUnary();
        }
        return parsePrimaryExpression();
    }

    private Expression parsePrimaryExpression() {
        JpqlToken t = peek();
        switch (t.type()) {
            case STRING -> {
                advance();
                return new Expression.Literal(t.text());
            }
            case NUMBER -> {
                advance();
                return new Expression.Literal(numberValue(t.text()));
            }
            case NAMED_PARAM -> {
                advance();
                return new Expression.NamedParameter(t.text());
            }
            case POSITIONAL_PARAM -> {
                advance();
                return new Expression.PositionalParameter(Integer.parseInt(t.text()));
            }
            case KEYWORD -> {
                String kw = t.upper();
                if (kw.equals("TRUE") || kw.equals("FALSE")) {
                    advance();
                    return new Expression.Literal(Boolean.valueOf(kw.equals("TRUE")));
                }
                if (kw.equals("NULL")) {
                    advance();
                    return new Expression.Literal(null);
                }
                if (kw.equals("CASE")) {
                    return parseCase();
                }
                if (kw.equals("TREAT") || kw.equals("TYPE")) {
                    throw syntax(kw + "(...) polymorphic expression is not supported in v1 (deferred to Wave2 W3)");
                }
                throw syntax("Unexpected keyword '" + t.text() + "' in expression");
            }
            case OPERATOR -> {
                if (t.text().equals("(")) {
                    advance();
                    if (isKeywordAt(index, "SELECT")) {
                        Subquery sub = parseSubquery();
                        expectOperator(")");
                        return new Expression.ScalarSubquery(sub);
                    }
                    Expression inner = parseExpression();
                    expectOperator(")");
                    return inner;
                }
                throw syntax("Unexpected operator '" + t.text() + "' in expression");
            }
            case IDENTIFIER -> {
                return parseIdentifierExpression();
            }
            default -> throw syntax("Unexpected " + describe(t) + " in expression");
        }
    }

    private Expression parseIdentifierExpression() {
        JpqlToken first = advance();
        String upper = first.text().toUpperCase(Locale.ROOT);
        // 집계 함수: COUNT/SUM/AVG/MIN/MAX '('
        if (AGGREGATE_NAMES.contains(upper) && isOperator("(")) {
            return parseAggregate(AggregateOp.valueOf(upper));
        }
        // 인자 없는 시간 함수
        if (NO_ARG_FUNCTIONS.contains(upper) && !isOperator("(")) {
            return new Expression.FunctionCall(upper, List.of());
        }
        // 일반 함수 호출
        if (isOperator("(")) {
            return parseFunctionCall(upper);
        }
        // 경로식: alias[.seg[.seg...]]
        List<String> segments = new ArrayList<>();
        while (consumeOperator(".")) {
            segments.add(expectIdentifier("path segment"));
        }
        return new Expression.Path(first.text(), segments);
    }

    private Expression parseAggregate(AggregateOp op) {
        expectOperator("(");
        boolean distinct = consumeKeyword("DISTINCT");
        Expression arg;
        if (op == AggregateOp.COUNT && isOperator("*")) {
            advance();
            arg = null; // COUNT(*)
        } else {
            arg = parseExpression();
        }
        expectOperator(")");
        return new Expression.Aggregate(op, distinct, arg);
    }

    private Expression parseFunctionCall(String name) {
        expectOperator("(");
        List<Expression> args = new ArrayList<>();
        if (!isOperator(")")) {
            args.add(parseExpression());
            while (consumeOperator(",")) {
                args.add(parseExpression());
            }
        }
        expectOperator(")");
        return new Expression.FunctionCall(name, args);
    }

    private Expression parseCase() {
        expectKeyword("CASE");
        if (!isKeyword("WHEN")) {
            throw syntax("Only searched CASE (CASE WHEN ... THEN ... END) is supported; "
                    + "simple CASE (CASE operand WHEN ...) is not supported in v1");
        }
        List<WhenClause> whens = new ArrayList<>();
        while (consumeKeyword("WHEN")) {
            Predicate condition = parsePredicate();
            expectKeyword("THEN");
            Expression result = parseExpression();
            whens.add(new WhenClause(condition, result));
        }
        Expression elseResult = consumeKeyword("ELSE") ? parseExpression() : null;
        expectKeyword("END");
        return new Expression.Case(whens, elseResult);
    }

    // ----------------------------------------------------------------------------------------
    // Subquery
    // ----------------------------------------------------------------------------------------

    private Subquery parseSubquery() {
        expectKeyword("SELECT");
        if (consumeKeyword("DISTINCT")) {
            throw syntax("DISTINCT inside a subquery is not supported in v1");
        }
        Expression selection;
        // EXISTS 서브쿼리는 흔히 SELECT 1 형태 — 리터럴/경로/집계 모두 허용
        selection = parseExpression();
        expectKeyword("FROM");
        String rootEntity = expectIdentifier("subquery entity name");
        String rootAlias = parseAlias("subquery root");
        List<JoinClause> joins = parseJoins();
        Predicate where = consumeKeyword("WHERE") ? parsePredicate() : null;
        List<Expression.Path> groupBy = parseGroupBy();
        Predicate having = consumeKeyword("HAVING") ? parsePredicate() : null;
        return new Subquery(selection, rootEntity, rootAlias, joins, where, groupBy, having);
    }

    // ----------------------------------------------------------------------------------------
    // Path / alias helpers
    // ----------------------------------------------------------------------------------------

    private Expression.Path parsePath() {
        String alias = expectIdentifier("path");
        List<String> segments = new ArrayList<>();
        while (consumeOperator(".")) {
            segments.add(expectIdentifier("path segment"));
        }
        return new Expression.Path(alias, segments);
    }

    /** 선택적 별칭({@code [AS] identifier})을 읽는다. 없으면 null. */
    private String parseAlias(String context) {
        if (consumeKeyword("AS")) {
            return expectIdentifier(context + " alias");
        }
        if (peek().type() == TokenType.IDENTIFIER) {
            return advance().text();
        }
        return null;
    }

    // ----------------------------------------------------------------------------------------
    // Token cursor primitives
    // ----------------------------------------------------------------------------------------

    private JpqlToken peek() {
        return tokens.get(index);
    }

    private JpqlToken advance() {
        return tokens.get(index++);
    }

    private boolean isKeyword(String kw) {
        return isKeywordAt(index, kw);
    }

    private boolean isKeywordAt(int at, String kw) {
        if (at >= tokens.size()) {
            return false;
        }
        JpqlToken t = tokens.get(at);
        return t.type() == TokenType.KEYWORD && t.upper().equals(kw);
    }

    private boolean isOperator(String op) {
        JpqlToken t = peek();
        return t.type() == TokenType.OPERATOR && t.text().equals(op);
    }

    private boolean consumeKeyword(String kw) {
        if (isKeyword(kw)) {
            index++;
            return true;
        }
        return false;
    }

    private boolean consumeOperator(String op) {
        if (isOperator(op)) {
            index++;
            return true;
        }
        return false;
    }

    private void expectKeyword(String kw) {
        if (!consumeKeyword(kw)) {
            throw syntax("Expected keyword '" + kw + "' but found " + describe(peek()));
        }
    }

    private void expectOperator(String op) {
        if (!consumeOperator(op)) {
            throw syntax("Expected '" + op + "' but found " + describe(peek()));
        }
    }

    private String expectIdentifier(String what) {
        JpqlToken t = peek();
        if (t.type() != TokenType.IDENTIFIER) {
            throw syntax("Expected " + what + " (identifier) but found " + describe(t));
        }
        index++;
        return t.text();
    }

    private JpqlToken expect(TokenType type, String what) {
        JpqlToken t = peek();
        if (t.type() != type) {
            throw syntax("Expected " + what + " but found " + describe(t));
        }
        index++;
        return t;
    }

    private void expectEof() {
        if (peek().type() != TokenType.EOF) {
            throw syntax("Unexpected trailing input: " + describe(peek()));
        }
    }

    private static boolean isComparisonSymbol(String s) {
        return switch (s) {
            case "=", "<>", "<", ">", "<=", ">=" -> true;
            default -> false;
        };
    }

    private static Object numberValue(String text) {
        if (text.indexOf('.') >= 0) {
            return new BigDecimal(text);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return new BigDecimal(text);
        }
    }

    private static String describe(JpqlToken t) {
        if (t.type() == TokenType.EOF) {
            return "end of input";
        }
        return t.type() + " '" + t.text() + "' (at position " + t.position() + ")";
    }

    private JpqlSyntaxException syntax(String message) {
        return new JpqlSyntaxException(message);
    }
}
