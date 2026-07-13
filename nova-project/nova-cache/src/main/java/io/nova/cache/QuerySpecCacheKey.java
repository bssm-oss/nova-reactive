package io.nova.cache;

import io.nova.query.CompoundPredicate;
import io.nova.query.Condition;
import io.nova.query.Cursor;
import io.nova.query.CursorField;
import io.nova.query.NegationPredicate;
import io.nova.query.Pageable;
import io.nova.query.Predicate;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;

import java.util.List;

/**
 * {@link QuerySpec}을 쿼리 캐시용 <b>정규화된(canonical) 문자열 키</b>로 변환한다. 같은 의미의 스펙은 항상
 * 같은 문자열을, 다른 스펙은 (실무상) 다른 문자열을 만들도록 스펙 트리를 결정적으로 직렬화한다.
 *
 * <h2>정규화 규칙</h2>
 * <ul>
 *   <li>predicate/sort/cursor 트리를 구조 순서대로 방문해 각 노드의 종류·연산자·프로퍼티·방향을 명시한다.</li>
 *   <li>바인딩 <b>값</b>은 값 자체({@code String.valueOf})와 함께 <b>런타임 타입</b>을 붙여 직렬화한다 —
 *       {@code Integer 5}와 {@code Long 5}가 같은 텍스트("5")로 충돌해 서로 다른 쿼리가 같은 키를 공유하는
 *       것을 막는다(그 충돌은 stale이 아니라 <i>잘못된 결과</i>를 서빙하므로 반드시 분리한다).</li>
 *   <li>{@link io.nova.query.LockMode}도 키에 포함하지만, 실제로는 잠금 쿼리를 캐시하지 않으므로(데코레이터가
 *       {@code LockMode.NONE}만 캐시) 방어적 성격이다.</li>
 * </ul>
 *
 * <p>알려진 경계: 사용자 정의 바인딩 값 타입은 자신의 {@code toString}이 값-구분적(value-distinguishing)이어야
 * 한다. 그렇지 않으면 서로 다른 값이 같은 키로 충돌할 수 있다 — 표준 스칼라(숫자/문자열/enum/불리언/시간)는
 * 안전하다.
 */
final class QuerySpecCacheKey {

    private QuerySpecCacheKey() {
    }

    /**
     * 엔티티 타입과 스펙으로부터 결정적 캐시 키 문자열을 만든다.
     */
    static String of(Class<?> entityType, QuerySpec spec) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(entityType.getName()).append("::");
        appendPredicate(sb, spec.predicate());
        sb.append("|sort=");
        appendSort(sb, spec.sort());
        sb.append("|page=");
        appendPageable(sb, spec.pageable());
        sb.append("|cursor=");
        appendCursor(sb, spec.cursor());
        sb.append("|lock=").append(spec.lockMode());
        return sb.toString();
    }

    private static void appendPredicate(StringBuilder sb, Predicate predicate) {
        if (predicate == null) {
            sb.append("P(null)");
            return;
        }
        if (predicate instanceof Condition c) {
            sb.append("C(").append(c.property()).append(',').append(c.operator()).append(',');
            appendValue(sb, c.value());
            sb.append(')');
        } else if (predicate instanceof CompoundPredicate cp) {
            sb.append("AND_OR(").append(cp.operator()).append(":[");
            List<Predicate> children = cp.predicates();
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendPredicate(sb, children.get(i));
            }
            sb.append("])");
        } else if (predicate instanceof NegationPredicate np) {
            sb.append("NOT(");
            appendPredicate(sb, np.inner());
            sb.append(')');
        } else {
            // 알 수 없는 predicate 종류: 클래스명 + toString으로 최선의 결정적 직렬화.
            sb.append("X(").append(predicate.getClass().getName()).append(':').append(predicate).append(')');
        }
    }

    private static void appendSort(StringBuilder sb, Sort sort) {
        if (sort == null) {
            sb.append("null");
            return;
        }
        sb.append('[');
        List<Sort.Order> orders = sort.orders();
        for (int i = 0; i < orders.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Sort.Order o = orders.get(i);
            sb.append(o.property()).append(' ').append(o.direction());
        }
        sb.append(']');
    }

    private static void appendPageable(StringBuilder sb, Pageable pageable) {
        if (pageable == null) {
            sb.append("null");
            return;
        }
        sb.append(pageable.limit()).append('@').append(pageable.offset());
    }

    private static void appendCursor(StringBuilder sb, Cursor cursor) {
        if (cursor == null) {
            sb.append("null");
            return;
        }
        sb.append('[');
        List<CursorField> fields = cursor.fields();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            CursorField f = fields.get(i);
            sb.append(f.property()).append(' ').append(f.direction()).append(' ');
            appendValue(sb, f.lastValue());
        }
        sb.append(']');
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        // 런타임 타입 + 값. 타입을 붙여 텍스트 동일-값-다른-타입 충돌을 방지한다.
        sb.append('(').append(value.getClass().getName()).append(')').append(value);
    }
}
