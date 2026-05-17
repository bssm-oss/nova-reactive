package io.nova.dialect.postgresql;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.Id;
import io.nova.annotation.Table;

@Entity
@Table("assigned_accounts")
class PostgresqlAssignedIdAccount {
    @Id
    private Long id;

    @Column("email_address")
    private String email;

    PostgresqlAssignedIdAccount() {
    }

    PostgresqlAssignedIdAccount(Long id, String email) {
        this.id = id;
        this.email = email;
    }
}
