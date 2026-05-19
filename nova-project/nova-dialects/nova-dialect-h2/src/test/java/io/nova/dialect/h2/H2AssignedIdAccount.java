package io.nova.dialect.h2;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.Id;
import io.nova.annotation.Table;

@Entity
@Table("assigned_accounts")
class H2AssignedIdAccount {
    @Id
    private Long id;

    @Column("email_address")
    private String email;

    H2AssignedIdAccount() {
    }

    H2AssignedIdAccount(Long id, String email) {
        this.id = id;
        this.email = email;
    }
}
