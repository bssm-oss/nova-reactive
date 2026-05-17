package io.nova.dialect.mysql;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Table;

@Entity
@Table("accounts")
class MySqlSampleAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column("email_address")
    private String email;

    @Column(nullable = false)
    private boolean active;

    MySqlSampleAccount() {
    }

    MySqlSampleAccount(Long id, String email, boolean active) {
        this.id = id;
        this.email = email;
        this.active = active;
    }
}
