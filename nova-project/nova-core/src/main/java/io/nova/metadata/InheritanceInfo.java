package io.nova.metadata;

import jakarta.persistence.DiscriminatorType;

import java.util.Objects;

/**
 * SINGLE_TABLE 상속(@{@link jakarta.persistence.Inheritance})에 참여하는 엔티티의 discriminator 메타데이터다.
 * 상속에 참여하지 않는 엔티티는 {@link #NONE}을 사용한다.
 *
 * <p>같은 계층의 모든 멤버(루트 + 서브타입)는 동일한 {@link #root()}/{@link #discriminatorColumn()}/
 * {@link #discriminatorType()}/{@link #discriminatorLength()}를 공유하고, {@link #discriminatorValue()}와
 * {@link #isRoot()}/{@link #abstractType()}만 멤버별로 달라진다.
 *
 * @param root              계층 루트 클래스(@{@link jakarta.persistence.Inheritance}가 선언된 최상위 엔티티)
 * @param isRoot            이 엔티티가 곧 루트인지 여부
 * @param abstractType      이 엔티티가 abstract라 실제 row 타입으로 인스턴스화되지 않는지 여부
 * @param discriminatorColumn discriminator 컬럼 이름
 * @param discriminatorType STRING / CHAR / INTEGER
 * @param discriminatorLength STRING discriminator 컬럼의 varchar 길이
 * @param discriminatorValue 이 구체 타입을 식별하는 discriminator 값(abstract 루트는 빈 문자열일 수 있음)
 */
public record InheritanceInfo(
        Class<?> root,
        boolean isRoot,
        boolean abstractType,
        String discriminatorColumn,
        DiscriminatorType discriminatorType,
        int discriminatorLength,
        String discriminatorValue
) {
    /**
     * 상속에 참여하지 않는 엔티티를 위한 sentinel. {@link #present()}가 {@code false}를 반환한다.
     */
    public static final InheritanceInfo NONE =
            new InheritanceInfo(null, false, false, "", null, 0, "");

    public InheritanceInfo {
        discriminatorColumn = discriminatorColumn == null ? "" : discriminatorColumn;
        discriminatorValue = discriminatorValue == null ? "" : discriminatorValue;
    }

    /**
     * 이 엔티티가 SINGLE_TABLE 상속 계층의 멤버이면 {@code true}. discriminator 컬럼 유무로 판정한다.
     */
    public boolean present() {
        return !discriminatorColumn.isBlank();
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
