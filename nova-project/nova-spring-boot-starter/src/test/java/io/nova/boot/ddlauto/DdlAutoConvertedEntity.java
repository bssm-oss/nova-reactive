package io.nova.boot.ddlauto;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ddl_auto_converted")
public class DdlAutoConvertedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private DdlAutoCode code;

    public DdlAutoConvertedEntity() {
    }

    public DdlAutoConvertedEntity(DdlAutoCode code) {
        this.code = code;
    }

    public Long getId() {
        return id;
    }

    public DdlAutoCode getCode() {
        return code;
    }
}
