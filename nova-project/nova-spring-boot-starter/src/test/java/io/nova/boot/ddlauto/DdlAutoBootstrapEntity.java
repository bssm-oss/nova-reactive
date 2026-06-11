package io.nova.boot.ddlauto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Test-only entity placed in its own package so that {@code nova.entity-packages}
 * can scope a SchemaBootstrapRunner scan without picking up the other test
 * doubles in {@code io.nova.boot}.
 */
@Entity
@Table(name = "ddl_auto_accounts")
public class DdlAutoBootstrapEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email_address")
    private String email;

    public DdlAutoBootstrapEntity() {}

    public DdlAutoBootstrapEntity(Long id, String email) {
        this.id = id;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }
}
