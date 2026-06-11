package io.nova.dialect.h2;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assigned_accounts")
class H2AssignedIdAccount {
    @Id
    private Long id;

    @Column(name = "email_address")
    private String email;

    H2AssignedIdAccount() {
    }

    H2AssignedIdAccount(Long id, String email) {
        this.id = id;
        this.email = email;
    }
}
