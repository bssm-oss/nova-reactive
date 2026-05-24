package io.nova.dialect.oracle;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.Enumerated;
import io.nova.annotation.EnumType;
import io.nova.annotation.Id;
import io.nova.annotation.Table;

/**
 * Oracle sqlType 매핑 전체 경로(Integer/Double/Boolean/Long/String + STRING/ORDINAL enum)를
 * 검증하기 위한 테스트 픽스처다.
 */
@Entity
@Table("type_accounts")
class OracleTypeAccount {
    enum Tier {
        FREE,
        PRO
    }

    @Id
    private Long id;

    @Column("balance")
    private int balance;

    @Column("ratio")
    private double ratio;

    @Column(value = "enabled", nullable = false)
    private boolean enabled;

    @Column("name")
    private String name;

    @Column("tier")
    @Enumerated(EnumType.STRING)
    private Tier tier;

    @Column("ordinal_tier")
    @Enumerated(EnumType.ORDINAL)
    private Tier ordinalTier;

    OracleTypeAccount() {
    }
}
