package io.nova.metadata;

/**
 * {@code @GeneratedValue(strategy = TABLE)} + {@code @TableGenerator} 매핑 메타데이터다.
 * 데이터베이스에 별도의 generator 테이블(예: {@code nova_sequences})을 두고, 그 한 행의 카운터 컬럼을
 * 증가시켜 식별자 블록을 발급한다. {@code @SequenceGenerator}/SEQUENCE 전략의 portable 대체물이며,
 * native 시퀀스를 지원하지 않는 데이터베이스에서도 동작한다.
 *
 * <p>이 info는 {@code @Id} {@link PersistentProperty}에 매달려 보관되며, generator 테이블 DDL/seed는
 * {@code SchemaGenerator}가, 다음 값 취득은 core operations가 dialect의 increment/select SQL을 통해
 * 트랜잭션/커넥션 컨텍스트 안에서 수행한다.
 *
 * @param table           generator 테이블 이름(여러 entity가 공유할 수 있다)
 * @param pkColumnName    generator 이름들을 구분하는 PK 컬럼 이름
 * @param valueColumnName 다음 값을 담는 카운터 컬럼 이름
 * @param pkColumnValue   이 generator의 행을 식별하는 PK 값(논리적 sequence 이름)
 * @param initialValue    seed 행의 시작 값. 첫 발급 id가 이 값이 되도록 seed한다.
 * @param allocationSize  한 번의 DB 왕복으로 미리 확보하는 식별자 블록 크기(>= 1). v1은 in-memory 블록
 *                        할당으로 왕복을 줄이되, 블록 안의 값은 순차적으로 소비한다.
 */
public record TableGeneratorInfo(
        String table,
        String pkColumnName,
        String valueColumnName,
        String pkColumnValue,
        long initialValue,
        int allocationSize
) {
    public TableGeneratorInfo {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("@TableGenerator table must not be blank");
        }
        if (pkColumnName == null || pkColumnName.isBlank()) {
            throw new IllegalArgumentException("@TableGenerator pkColumnName must not be blank");
        }
        if (valueColumnName == null || valueColumnName.isBlank()) {
            throw new IllegalArgumentException("@TableGenerator valueColumnName must not be blank");
        }
        if (pkColumnValue == null || pkColumnValue.isBlank()) {
            throw new IllegalArgumentException("@TableGenerator pkColumnValue must not be blank");
        }
        if (allocationSize < 1) {
            throw new IllegalArgumentException(
                    "@TableGenerator allocationSize must be >= 1 but was " + allocationSize);
        }
    }
}
