package io.nova.metadata;

import java.util.Objects;

/**
 * {@code @OrderColumn} 매핑 메타데이터 — 정렬된 {@code List} 컬렉션의 물리 순서를 보관하는 전용 정수 컬럼.
 * <p>
 * {@code @OrderBy}(엔티티 속성 기준 정렬)와는 별개로, 컬렉션 원소의 인덱스(0..n-1)를 {@link #columnName()}
 * 컬럼에 직접 기록한다. save 시 reconcile 단계가 현재 컬렉션 순서를 인덱스로 써넣고, read 시 hydration 단계가
 * 이 컬럼을 ORDER BY ASC로 읽어 {@code List} 순서를 복원한다. 컬럼 SQL 타입은 항상 정수형이다.
 * <p>
 * {@code @ElementCollection}의 {@code List} 필드에서는 collection table에 이 컬럼을 둔다.
 * {@code @OneToMany(mappedBy)}의 {@code List}에서는 child 테이블에 이 컬럼을 둔다(별도 join 테이블이 없는
 * mappedBy 형태). {@code @ManyToMany}/{@code Set}/단일 관계에서는 {@link EntityMetadataFactory}가
 * fail-fast로 거부한다.
 * <p>
 * <b>null 원소 정책(dense-list):</b> Nova는 정렬 컬렉션을 dense {@code List}(0..n-1 빈틈 없는 인덱스)로
 * 가정한다. reconcile(save) 단계는 컬렉션의 {@code null} 원소를 silent하게 압축(skip)하여 0-기반 연속
 * 인덱스로 다시 써넣고, hydration(read)은 그 연속 인덱스를 그대로 복원한다. 따라서 sparse {@code List}
 * (중간 {@code null} 슬롯으로 인덱스 간격을 보존하는 형태)는 지원하지 않으며, 저장 후 재조회하면 {@code null}
 * 슬롯이 사라지고 뒤 원소가 앞으로 당겨진다. JPA 스펙은 sparse 보존을 권고하지만, Nova는 (a) reconcile이
 * 이미 full-replace라 인덱스를 항상 재계산하고 (b) {@code null} 원소를 별도 row로 보존하면 hydration이
 * {@code null}을 다시 만들어 도메인 코드의 NPE를 유발하기 쉬우므로, 예측 가능한 dense 의미를 택했다.
 */
public record OrderColumnInfo(String columnName) {
    public OrderColumnInfo {
        Objects.requireNonNull(columnName, "columnName must not be null");
        if (columnName.isBlank()) {
            throw new IllegalArgumentException("@OrderColumn columnName must not be blank");
        }
    }
}
