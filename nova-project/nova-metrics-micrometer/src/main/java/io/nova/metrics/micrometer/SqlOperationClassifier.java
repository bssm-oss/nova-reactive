package io.nova.metrics.micrometer;

/**
 * {@link io.nova.sql.SqlStatement#sql()} 문자열의 선행 키워드를 근거로 SQL을
 * {@code save} / {@code find} / {@code delete} / {@code query} 4개 operation 버킷으로 분류한다.
 * <p>
 * {@link io.nova.core.SqlExecutionListener}는 호출자가 어떤 {@code ReactiveEntityOperations}
 * 메서드를 통해 실행됐는지 전달하지 않으므로(코어 계약 변경 없이는 알 수 없음), 이 분류는 SQL 텍스트의
 * 선행 키워드만으로 근사한다:
 * <ul>
 *   <li>{@code INSERT}, {@code UPDATE} → {@code save} (session flush의 insert/update 모두 저장 경로)</li>
 *   <li>{@code DELETE} → {@code delete}</li>
 *   <li>{@code SELECT} → {@code find}</li>
 *   <li>그 외(DDL, CTE로 시작하는 {@code WITH}, 알 수 없는 구문 등) → {@code query} fallback</li>
 * </ul>
 * 이 근사는 완벽하지 않다 — 예를 들어 {@code WITH} CTE로 시작하는 SELECT는 {@code query}로 분류된다.
 * 하지만 core 계약을 변경하지 않고 operation 단위 latency를 노출하기 위한 실용적 절충이다.
 */
final class SqlOperationClassifier {

    static final String SAVE = "save";
    static final String FIND = "find";
    static final String DELETE = "delete";
    static final String QUERY = "query";

    private SqlOperationClassifier() {
    }

    static String classify(String sql) {
        if (sql == null) {
            return QUERY;
        }
        String keyword = leadingKeyword(sql);
        return switch (keyword) {
            case "INSERT", "UPDATE" -> SAVE;
            case "DELETE" -> DELETE;
            case "SELECT" -> FIND;
            default -> QUERY;
        };
    }

    private static String leadingKeyword(String sql) {
        int start = 0;
        int length = sql.length();
        while (start < length && Character.isWhitespace(sql.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < length && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        return sql.substring(start, end).toUpperCase(java.util.Locale.ROOT);
    }
}
