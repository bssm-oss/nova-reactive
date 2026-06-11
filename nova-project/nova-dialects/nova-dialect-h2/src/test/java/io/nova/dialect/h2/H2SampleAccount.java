package io.nova.dialect.h2;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
class H2SampleAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email_address")
    private String email;

    @Column(nullable = false)
    private boolean active;

    H2SampleAccount() {
    }

    H2SampleAccount(String email, boolean active) {
        this.email = email;
        this.active = active;
    }
}
