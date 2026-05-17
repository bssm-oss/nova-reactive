package io.nova.dialect.postgresql;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Table;

@Entity
@Table("accounts")
class PostgresqlSampleAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column("email_address")
    private String email;

    @Column(nullable = false)
    private boolean active;

    PostgresqlSampleAccount() {
    }

    PostgresqlSampleAccount(String email, boolean active) {
        this.email = email;
        this.active = active;
    }
}
