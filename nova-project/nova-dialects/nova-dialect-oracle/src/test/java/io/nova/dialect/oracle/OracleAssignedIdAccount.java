package io.nova.dialect.oracle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assigned_accounts")
class OracleAssignedIdAccount {
    @Id
    private Long id;

    @Column(name = "email_address")
    private String email;

    OracleAssignedIdAccount() {
    }

    OracleAssignedIdAccount(Long id, String email) {
        this.id = id;
        this.email = email;
    }
}
