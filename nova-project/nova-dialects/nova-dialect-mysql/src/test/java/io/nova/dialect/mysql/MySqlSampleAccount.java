package io.nova.dialect.mysql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
class MySqlSampleAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email_address")
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
