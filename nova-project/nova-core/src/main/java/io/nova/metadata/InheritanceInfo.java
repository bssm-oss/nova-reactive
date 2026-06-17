package io.nova.metadata;

import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.InheritanceType;

import java.util.Objects;

/**
 * 상속(@{@link jakarta.persistence.Inheritance})에 참여하는 엔티티의 discriminator/전략 메타데이터다.
 * 상속에 참여하지 않는 엔티티는 {@link #NONE}을 사용한다.
 *
 * <p>같은 계층의 모든 멤버(루트 + 서브타입)는 동일한 {@link #root()}/{@link #strategy()}/
 * {@link #discriminatorColumn()}/{@link #discriminatorType()}/{@link #discriminatorLength()}를 공유하고,
 * {@link #discriminatorValue()}와 {@link #isRoot()}/{@link #abstractType()}만 멤버별로 달라진다.
 *
 * <p>전략별 의미:
 * <ul>
 *   <li><b>SINGLE_TABLE</b>: 루트 테이블 하나에 전 서브타입 컬럼을 union하고 discriminator로 구분한다.</li>
 *   <li><b>JOINED</b>: 루트 테이블(공통 컬럼 + discriminator) + 서브타입별 테이블(서브타입 컬럼 + 루트 PK를 FK로
 *       공유)을 둔다. {@link #rootTableName()}이 루트 물리 테이블, 각 멤버의 {@link EntityMetadata#tableName()}이
 *       자기 테이블이다.</li>
 *   <li><b>TABLE_PER_CLASS</b>: 각 구체 서브타입이 모든 상속 컬럼을 독립 테이블에 담는다. 공유 테이블이 없고
 *       discriminator는 다형 UNION 쿼리에서 합성 상수 컬럼으로만 등장한다.</li>
 * </ul>
 *
 * @param root              계층 루트 클래스(@{@link jakarta.persistence.Inheritance}가 선언된 최상위 엔티티)
 * @param strategy          상속 전략(SINGLE_TABLE / JOINED / TABLE_PER_CLASS)
 * @param isRoot            이 엔티티가 곧 루트인지 여부
 * @param abstractType      이 엔티티가 abstract라 실제 row 타입으로 인스턴스화되지 않는지 여부
 * @param discriminatorColumn discriminator 컬럼 이름
 * @param discriminatorType STRING / CHAR / INTEGER
 * @param discriminatorLength STRING discriminator 컬럼의 varchar 길이
 * @param discriminatorValue 이 구체 타입을 식별하는 discriminator 값(abstract 루트는 빈 문자열일 수 있음)
 * @param rootTableName     JOINED에서 루트 물리 테이블 이름(SINGLE_TABLE/TPC에서는 빈 문자열일 수 있음)
 * @param rootIdColumn      JOINED에서 루트 PK 컬럼 이름(서브타입 테이블이 FK로 공유). SINGLE_TABLE/TPC는 빈 문자열.
 */
public record InheritanceInfo(
        Class<?> root,
        InheritanceType strategy,
        boolean isRoot,
        boolean abstractType,
        String discriminatorColumn,
        DiscriminatorType discriminatorType,
        int discriminatorLength,
        String discriminatorValue,
        String rootTableName,
        String rootIdColumn
) {
    /**
     * 상속에 참여하지 않는 엔티티를 위한 sentinel. {@link #present()}가 {@code false}를 반환한다.
     */
    public static final InheritanceInfo NONE =
            new InheritanceInfo(null, InheritanceType.SINGLE_TABLE, false, false,
                    "", null, 0, "", "", "");

    public InheritanceInfo {
        strategy = strategy == null ? InheritanceType.SINGLE_TABLE : strategy;
        discriminatorColumn = discriminatorColumn == null ? "" : discriminatorColumn;
        discriminatorValue = discriminatorValue == null ? "" : discriminatorValue;
        rootTableName = rootTableName == null ? "" : rootTableName;
        rootIdColumn = rootIdColumn == null ? "" : rootIdColumn;
    }

    /**
     * 하위 호환 생성자 — SINGLE_TABLE 전용 7-인자 형태. 기존 호출부가 깨지지 않도록 유지한다.
     */
    public InheritanceInfo(
            Class<?> root,
            boolean isRoot,
            boolean abstractType,
            String discriminatorColumn,
            DiscriminatorType discriminatorType,
            int discriminatorLength,
            String discriminatorValue
    ) {
        this(root, InheritanceType.SINGLE_TABLE, isRoot, abstractType,
                discriminatorColumn, discriminatorType, discriminatorLength, discriminatorValue, "", "");
    }

    /**
     * 이 엔티티가 상속 계층의 멤버이면 {@code true}. discriminator 컬럼 유무로 판정한다.
     * SINGLE_TABLE/JOINED는 discriminator 컬럼을 가지며, TABLE_PER_CLASS도 다형 UNION 쿼리에서 합성
     * discriminator를 쓰므로 컬럼 이름이 채워져 있다.
     */
    public boolean present() {
        return !discriminatorColumn.isBlank();
    }

    /**
     * SINGLE_TABLE 전략 여부.
     */
    public boolean singleTable() {
        return strategy == InheritanceType.SINGLE_TABLE;
    }

    /**
     * JOINED 전략 여부.
     */
    public boolean joined() {
        return strategy == InheritanceType.JOINED;
    }

    /**
     * TABLE_PER_CLASS 전략 여부.
     */
    public boolean tablePerClass() {
        return strategy == InheritanceType.TABLE_PER_CLASS;
    }

    /**
     * 이 멤버의 discriminator 값을, discriminatorType에 맞는 SQL 바인딩 값으로 변환한다.
     * STRING/CHAR는 문자열 그대로, INTEGER는 {@link Integer}로 파싱한다.
     */
    public Object discriminatorBindValue() {
        if (discriminatorType == DiscriminatorType.INTEGER) {
            return Integer.valueOf(discriminatorValue.trim());
        }
        return discriminatorValue;
    }

    /**
     * 동일 계층에 속하는지 비교한다(같은 root).
     */
    public boolean sameHierarchy(InheritanceInfo other) {
        return present() && other.present() && Objects.equals(root, other.root);
    }

    /**
     * row에서 discriminator 컬럼을 디코딩할 Java 타입. INTEGER는 {@link Integer}, STRING/CHAR는
     * {@link String}.
     */
    public Class<?> discriminatorJavaType() {
        return discriminatorType == DiscriminatorType.INTEGER ? Integer.class : String.class;
    }
}
