package io.nova.dialect.oracle;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.Id;
import io.nova.annotation.Table;

@Entity
@Table("assigned_accounts")
class OracleAssignedIdAccount {
    @Id
    private Long id;

    @Column("email_address")
    private String email;

    OracleAssignedIdAccount() {
    }

    OracleAssignedIdAccount(Long id, String email) {
        this.id = id;
        this.email = email;
    }
}
