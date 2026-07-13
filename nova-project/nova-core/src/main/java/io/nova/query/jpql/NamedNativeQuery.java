package io.nova.query.jpql;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.query.NativeQuery;
import io.nova.sql.Dialect;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 등록된 {@code @NamedNativeQuery} 한 건에 파라미터를 바인딩해 리액티브로 실행하는 핸들. JPA
 * {@code TypedQuery}(네이티브)의 리액티브 등가물로, {@code block()} 없이 {@link Flux}/{@link Mono}만 반환한다.
 * <p>
 * 네이티브 SQL의 JPA 스타일 파라미터 마커({@code :name}, {@code ?n})를 dialect의
 * {@link io.nova.sql.BindMarkerStrategy}가 정의한 positional 마커로 치환하고, 바인딩 값을 마커 출현 순서로
 * 정렬해 {@link ReactiveEntityOperations#queryNative}/{@link ReactiveEntityOperations#executeNative}에
 * 위임한다. SELECT 결과 매핑은 생성 시 주입된 {@code mapper}(엔티티 {@code resultClass} 매핑 또는 사용자 지정)를
 * 사용한다.
 *
 * @param <T> 결과 원소 타입
 */
public final class NamedNativeQuery<T> {

    private final String sql;
    private final Function<RowAccessor, T> mapper;
    private final ReactiveEntityOperations operations;
    private final String translatedSql;
    private final List<Object> markerKeys;

    private final Map<String, Object> namedValues = new HashMap<>();
    private final Map<Integer, Object> positionalValues = new HashMap<>();

    NamedNativeQuery(
            String sql,
            Function<RowAccessor, T> mapper,
            ReactiveEntityOperations operations,
            Dialect dialect) {
        this.sql = Objects.requireNonNull(sql, "sql must not be null");
        this.mapper = mapper;
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");
        List<Object> keys = new ArrayList<>();
        this.translatedSql = translate(sql, dialect, keys);
        this.markerKeys = keys;
    }

    public NamedNativeQuery<T> setParameter(String name, Object value) {
        Objects.requireNonNull(name, "parameter name must not be null");
        namedValues.put(name, value);
        return this;
    }

    public NamedNativeQuery<T> setParameter(int position, Object value) {
        positionalValues.put(position, value);
        return this;
    }

    // ----------------------------------------------------------------------------------------
    // Execution
    // ----------------------------------------------------------------------------------------

    /** SELECT 결과 목록을 발행한다. 결과 매핑이 설정되지 않은 쿼리(예: resultClass 없는 네이티브)면 에러 신호. */
    public Flux<T> getResultList() {
        if (mapper == null) {
            return Flux.error(new NamedQueryException(
                    "native query '" + sql + "' has no result mapping; declare a resultClass on @NamedNativeQuery "
                            + "or use createNativeQuery(name, mapper), and use executeUpdate() for INSERT/UPDATE/DELETE"));
        }
        return Flux.defer(() -> operations.queryNative(toNativeQuery(), mapper));
    }

    /**
     * 정확히 한 건의 결과를 발행한다. 결과가 없으면 에러(JPA {@code NoResultException} 등가), 두 건 이상이면
     * 에러(JPA {@code NonUniqueResultException} 등가)를 낸다.
     */
    public Mono<T> getSingleResult() {
        return getResultList().take(2).collectList().flatMap(list -> {
            if (list.isEmpty()) {
                return Mono.error(new NamedQueryException("getSingleResult() found no rows"));
            }
            if (list.size() > 1) {
                return Mono.error(new NamedQueryException("getSingleResult() found more than one row"));
            }
            return Mono.just(list.get(0));
        });
    }

    /** INSERT/UPDATE/DELETE 네이티브 문을 실행하고 영향 행 수를 발행한다. */
    public Mono<Long> executeUpdate() {
        return Mono.defer(() -> operations.executeNative(toNativeQuery()));
    }

    // ----------------------------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------------------------

    private NativeQuery toNativeQuery() {
        List<Object> values = new ArrayList<>(markerKeys.size());
        for (Object key : markerKeys) {
            if (key instanceof Integer position) {
                if (!positionalValues.containsKey(position)) {
                    throw new NamedQueryException("Missing binding for positional parameter ?" + position);
                }
                values.add(positionalValues.get(position));
            } else {
                String name = (String) key;
                if (!namedValues.containsKey(name)) {
                    throw new NamedQueryException("Missing binding for named parameter :" + name);
                }
                values.add(namedValues.get(name));
            }
        }
        return new NativeQuery(translatedSql, values);
    }

    /**
     * JPA 스타일 파라미터 마커({@code :name}, {@code ?n})를 dialect positional 마커로 치환하고, 마커 출현
     * 순서를 {@code markerKeys}에 기록한다. 작은따옴표 문자열 리터럴 내부와 PostgreSQL {@code ::} 캐스트
     * 연산자는 파라미터로 오인하지 않는다.
     */
    private static String translate(String sql, Dialect dialect, List<Object> markerKeys) {
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // 문자열 리터럴은 통째로 복사한다. '' 는 escaped quote이므로 리터럴을 계속 이어간다.
                out.append(c);
                i++;
                while (i < length) {
                    char lit = sql.charAt(i);
                    out.append(lit);
                    i++;
                    if (lit == '\'') {
                        if (i < length && sql.charAt(i) == '\'') {
                            out.append('\'');
                            i++;
                            continue;
                        }
                        break;
                    }
                }
                continue;
            }
            if (c == ':') {
                if (i + 1 < length && sql.charAt(i + 1) == ':') {
                    // PostgreSQL 캐스트 연산자 '::' — 파라미터가 아니다.
                    out.append("::");
                    i += 2;
                    continue;
                }
                if (i + 1 < length && isIdentifierStart(sql.charAt(i + 1))) {
                    int start = i + 1;
                    int end = start + 1;
                    while (end < length && isIdentifierPart(sql.charAt(end))) {
                        end++;
                    }
                    String name = sql.substring(start, end);
                    out.append(dialect.bindMarkers().marker(markerKeys.size()));
                    markerKeys.add(name);
                    i = end;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            if (c == '?' && i + 1 < length && Character.isDigit(sql.charAt(i + 1))) {
                int start = i + 1;
                int end = start + 1;
                while (end < length && Character.isDigit(sql.charAt(end))) {
                    end++;
                }
                int position = Integer.parseInt(sql.substring(start, end));
                out.append(dialect.bindMarkers().marker(markerKeys.size()));
                markerKeys.add(position);
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
