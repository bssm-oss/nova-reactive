package io.nova.dialect.postgresql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assigned_accounts")
class PostgresqlAssignedIdAccount {
    @Id
    private Long id;

    @Column(name = "email_address")
    private String email;

    PostgresqlAssignedIdAccount() {
    }

    PostgresqlAssignedIdAccount(Long id, String email) {
        this.id = id;
        this.email = email;
    }
}
