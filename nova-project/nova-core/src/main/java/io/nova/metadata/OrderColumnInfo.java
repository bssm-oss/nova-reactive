package io.nova.metadata;

import java.util.Objects;

/**
 * {@code @OrderColumn} 매핑 메타데이터 — 정렬된 {@code List} 컬렉션의 물리 순서를 보관하는 전용 정수 컬럼.
 * <p>
 * {@code @OrderBy}(엔티티 속성 기준 정렬)와는 별개로, 컬렉션 원소의 인덱스(0..n-1)를 {@link #columnName()}
 * 컬럼에 직접 기록한다. save 시 reconcile 단계가 현재 컬렉션 순서를 인덱스로 써넣고, read 시 hydration 단계가
 * 이 컬럼을 ORDER BY ASC로 읽어 {@code List} 순서를 복원한다. 컬럼 SQL 타입은 항상 정수형이다.
 * <p>
 * v1에서는 {@code @ElementCollection}의 {@code List} 필드에만 적용된다 — collection table에 이 컬럼을 둔다.
 * {@code @OneToMany}/{@code @ManyToMany}/{@code Set}/단일 관계에서는 {@link EntityMetadataFactory}가
 * fail-fast로 거부한다.
 */
public record OrderColumnInfo(String columnName) {
    public OrderColumnInfo {
        Objects.requireNonNull(columnName, "columnName must not be null");
        if (columnName.isBlank()) {
            throw new IllegalArgumentException("@OrderColumn columnName must not be blank");
        }
    }
}
