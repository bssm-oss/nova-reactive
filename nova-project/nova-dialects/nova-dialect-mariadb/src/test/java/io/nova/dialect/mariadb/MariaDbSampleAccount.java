package io.nova.dialect.mariadb;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Table;

@Entity
@Table("accounts")
class MariaDbSampleAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column("email_address")
    private String email;

    @Column(nullable = false)
    private boolean active;

    MariaDbSampleAccount() {
    }

    MariaDbSampleAccount(Long id, String email, boolean active) {
        this.id = id;
        this.email = email;
        this.active = active;
    }
}
