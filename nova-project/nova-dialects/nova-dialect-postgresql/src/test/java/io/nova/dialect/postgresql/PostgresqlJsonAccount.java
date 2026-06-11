package io.nova.dialect.postgresql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import io.nova.annotation.Json;
import jakarta.persistence.Table;

import java.util.Map;

/**
 * PostgreSQL dialect의 {@code jsonb} 컬럼 타입 렌더링을 검증하는 fixture entity다.
 * {@code metadata} 필드는 {@code @Json}으로 표시되며 DDL 생성 시 dialect가 {@code jsonb} 타입을 emit해야 한다.
 */
@Entity
@Table(name = "json_accounts")
class PostgresqlJsonAccount {
    @Id
    private Long id;

    @Json
    @Column(name = "payload")
    private Map<String, Object> metadata;

    PostgresqlJsonAccount() {
    }
}
