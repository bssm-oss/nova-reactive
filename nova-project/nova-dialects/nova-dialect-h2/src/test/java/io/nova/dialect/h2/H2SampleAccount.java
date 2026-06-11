package io.nova.dialect.h2;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Table;

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
