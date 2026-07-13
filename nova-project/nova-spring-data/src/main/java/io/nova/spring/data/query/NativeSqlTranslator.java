package io.nova.spring.data.query;

import io.nova.sql.BindMarkerStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * native {@code @Query} SQL 문자열의 Spring 스타일 플레이스홀더({@code :name}, {@code ?n})를 dialect
 * bind marker로 치환하고, 출현 순서대로 정렬된 binding 값 목록을 만든다.
 *
 * <p>같은 named 파라미터가 여러 번 등장하면 각 출현마다 별도 marker/binding을 만들어 값이 복제된다 —
 * R2DBC positional 바인딩 계약과 일치한다. {@code ::}(예: PostgreSQL cast)는 플레이스홀더가 아니라
 * 리터럴로 통과시킨다. 문자열 리터럴 안의 {@code :}/{@code ?}는 v1에서 특별 처리하지 않는다.
 */
public final class NativeSqlTranslator {

    /** 치환 결과. */
    public record Translated(String sql, List<Object> bindings) {
    }

    private NativeSqlTranslator() {
    }

    public static Translated translate(String sql, Map<String, Object> named, Map<Integer, Object> positional,
                                       BindMarkerStrategy markers) {
        StringBuilder out = new StringBuilder(sql.length());
        List<Object> bindings = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
                // PostgreSQL '::' cast — 그대로 통과.
                out.append("::");
                i += 2;
                continue;
            }
            if (c == ':' && i + 1 < n && Character.isJavaIdentifierStart(sql.charAt(i + 1))) {
                int start = i + 1;
                int j = start;
                while (j < n && Character.isJavaIdentifierPart(sql.charAt(j))) {
                    j++;
                }
                String name = sql.substring(start, j);
                if (!named.containsKey(name)) {
                    throw new AnnotatedQueryException(
                            "native @Query references :" + name + " but no @Param/argument binds it");
                }
                out.append(markers.marker(bindings.size()));
                bindings.add(named.get(name));
                i = j;
                continue;
            }
            if (c == '?' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
                int start = i + 1;
                int j = start;
                while (j < n && Character.isDigit(sql.charAt(j))) {
                    j++;
                }
                int position = Integer.parseInt(sql.substring(start, j));
                if (!positional.containsKey(position)) {
                    throw new AnnotatedQueryException(
                            "native @Query references ?" + position + " but there is no argument at that position");
                }
                out.append(markers.marker(bindings.size()));
                bindings.add(positional.get(position));
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return new Translated(out.toString(), bindings);
    }
}
