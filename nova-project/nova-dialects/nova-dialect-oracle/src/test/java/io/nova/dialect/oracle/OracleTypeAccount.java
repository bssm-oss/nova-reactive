package io.nova.dialect.oracle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Oracle sqlType 매핑 전체 경로(Integer/Double/Boolean/Long/String + STRING/ORDINAL enum)를
 * 검증하기 위한 테스트 픽스처다.
 */
@Entity
@Table(name = "type_accounts")
class OracleTypeAccount {
    enum Tier {
        FREE,
        PRO
    }

    @Id
    private Long id;

    @Column(name = "balance")
    private int balance;

    @Column(name = "ratio")
    private double ratio;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "name")
    private String name;

    @Column(name = "tier")
    @Enumerated(EnumType.STRING)
    private Tier tier;

    @Column(name = "ordinal_tier")
    @Enumerated(EnumType.ORDINAL)
    private Tier ordinalTier;

    OracleTypeAccount() {
    }
}
