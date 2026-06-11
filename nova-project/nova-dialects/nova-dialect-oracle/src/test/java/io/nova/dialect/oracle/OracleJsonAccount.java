package io.nova.dialect.oracle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import io.nova.annotation.Json;
import jakarta.persistence.Table;

/**
 * {@code @Json} 컬럼의 Oracle SQL 타입 매핑을 검증하는 fixture. {@code payload}의 javaType이 {@link String}이라,
 * sqlType override에 json 가드가 없으면 String 분기에서 {@code varchar2(255)}로 잘못 매핑된다 — 가드가 있으면
 * dialect의 jsonColumnType()을 따른다.
 */
@Entity
@Table(name = "json_accounts")
class OracleJsonAccount {
    @Id
    private Long id;

    @Json
    @Column(name = "payload")
    private String payload;

    OracleJsonAccount() {
    }
}
