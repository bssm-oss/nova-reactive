package io.nova.metadata;

/**
 * JPA {@code @SecondaryTable}로 선언된 보조 테이블 매핑 메타데이터. 엔티티의 일부 컬럼(개별
 * {@code @Column(table="...")}로 라우팅된 컬럼)을 primary 테이블이 아닌 이 보조 테이블 행에 저장하고,
 * primary PK 값을 PK 조인 컬럼으로 1:1 공유한다.
 *
 * <p>v1은 단일 {@code @Id}(복합키 아님) owner만 지원하므로 PK 조인 컬럼도 단일 컬럼이다.
 *
 * @param tableName       보조 테이블의 물리 이름
 * @param schema          보조 테이블 스키마. 미지정이면 빈 문자열이며, 이 경우 스키마 한정 없이 렌더된다.
 * @param pkJoinColumn    보조 테이블에서 primary PK를 공유하는 조인 컬럼 이름
 *                        ({@code @PrimaryKeyJoinColumn(name=...)}; 미지정이면 primary PK 컬럼 이름).
 * @param primaryKeyColumn 보조 테이블 조인 컬럼이 참조하는 primary 테이블의 PK 컬럼 이름
 *                        ({@code @PrimaryKeyJoinColumn(referencedColumnName=...)}; 미지정이면 primary PK 컬럼 이름).
 */
public record SecondaryTableInfo(
        String tableName,
        String schema,
        String pkJoinColumn,
        String primaryKeyColumn
) {
    public SecondaryTableInfo {
        schema = schema == null ? "" : schema;
    }

    /**
     * 스키마 없는 보조 테이블용 생성자.
     */
    public SecondaryTableInfo(String tableName, String pkJoinColumn, String primaryKeyColumn) {
        this(tableName, "", pkJoinColumn, primaryKeyColumn);
    }
}
