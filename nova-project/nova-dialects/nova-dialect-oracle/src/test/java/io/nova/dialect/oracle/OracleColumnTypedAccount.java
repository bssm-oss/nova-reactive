package io.nova.dialect.oracle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Oracle sqlType의 {@code @Column(length/precision/scale)} 매핑을 검증하기 위한 픽스처다.
 * <ul>
 *   <li>{@code shortName}은 {@code @Column(length=64)}로 {@code varchar2(64)}</li>
 *   <li>{@code description}은 length 미지정으로 기본 {@code varchar2(255)}</li>
 *   <li>{@code price}는 {@code @Column(precision=12, scale=2)}로 {@code number(12, 2)}</li>
 *   <li>{@code defaultDecimal}은 precision 미지정으로 기본 {@code number(19, 2)}</li>
 * </ul>
 */
@Entity
@Table(name = "column_typed")
class OracleColumnTypedAccount {
    @Id
    private Long id;

    @Column(name = "short_name", length = 64)
    private String shortName;

    @Column(name = "description")
    private String description;

    @Column(name = "price", precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "default_decimal")
    private BigDecimal defaultDecimal;

    OracleColumnTypedAccount() {
    }
}
