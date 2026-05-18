package io.nova.query;

import java.util.List;
import java.util.Objects;

/**
 * keyset(cursor) pagination에서 마지막으로 본 행의 정렬 키 모음이다. SQL 렌더 시 lexicographic
 * 비교를 펼쳐 "이 cursor 이후 N개"를 가져오는 WHERE 절을 만든다.
 * <p>
 * 다중 필드 cursor는 동률 발생 시 다음 필드로 tie-break하기 위해 정렬 키 순서대로 나열돼야 한다.
 * 예: {@code Cursor.of(CursorField.desc("createdAt", t), CursorField.asc("id", lastId))}.
 * <p>
 * 빈 cursor는 거부된다 — keyset pagination은 최소 한 개의 정렬 키가 필요하다.
 */
public record Cursor(List<CursorField> fields) {
    public Cursor {
        Objects.requireNonNull(fields, "fields must not be null");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("Cursor requires at least one field");
        }
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i) == null) {
                throw new IllegalArgumentException("Cursor field at index " + i + " is null");
            }
        }
        fields = List.copyOf(fields);
    }

    public static Cursor of(CursorField... fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        return new Cursor(List.of(fields));
    }
}
