package io.nova.schema;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.ForeignKeyDefinition;
import io.nova.support.fixtures.FixtureEntities.FkChildConstrained;
import io.nova.support.fixtures.FixtureEntities.FkChildDefault;
import io.nova.support.fixtures.FixtureEntities.FkChildSuppressed;
import io.nova.support.fixtures.FixtureEntities.FkElementOwner;
import io.nova.support.fixtures.FixtureEntities.FkParent;
import io.nova.support.fixtures.FixtureEntities.FkStudent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ForeignKeyConstraints} 해석 단위 테스트 — {@code @ForeignKey} ConstraintMode honor 규칙
 * (CONSTRAINT 발행 / NO_CONSTRAINT 억제 / PROVIDER_DEFAULT 미발행)과 link/collection table FK 해석을 검증한다.
 */
class ForeignKeyConstraintsTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void manyToOneWithConstraintModeEmitsNamedForeignKey() {
        List<ForeignKeyDefinition> defs =
                ForeignKeyConstraints.resolve(List.of(FkChildConstrained.class), factory);

        assertEquals(1, defs.size());
        ForeignKeyDefinition fk = defs.get(0);
        assertEquals("fk_child_constrained", fk.table());
        assertEquals("fk_child_parent", fk.constraintName());
        assertEquals(List.of("parent_id"), fk.columns());
        assertEquals("fk_parent", fk.referencedTable());
        assertEquals(List.of("id"), fk.referencedColumns());
    }

    @Test
    void manyToOneWithNoConstraintSuppressesForeignKey() {
        assertTrue(ForeignKeyConstraints.resolve(List.of(FkChildSuppressed.class), factory).isEmpty());
    }

    @Test
    void manyToOneWithoutForeignKeyAnnotationEmitsNothing() {
        // @JoinColumn만 있고 @ForeignKey 부재 → PROVIDER_DEFAULT → Nova 기존 동작(FK 미발행).
        assertTrue(ForeignKeyConstraints.resolve(List.of(FkChildDefault.class), factory).isEmpty());
    }

    @Test
    void manyToManyJoinTableEmitsBothOwnerAndInverseForeignKeys() {
        List<ForeignKeyDefinition> defs =
                ForeignKeyConstraints.resolve(List.of(FkStudent.class), factory);

        assertEquals(2, defs.size());

        ForeignKeyDefinition owner = defs.get(0);
        assertEquals("fk_enrollment", owner.table());
        assertEquals("fk_enr_student", owner.constraintName());
        assertEquals(List.of("student_id"), owner.columns());
        assertEquals("fk_student", owner.referencedTable());
        assertEquals(List.of("id"), owner.referencedColumns());

        ForeignKeyDefinition inverse = defs.get(1);
        assertEquals("fk_enrollment", inverse.table());
        assertEquals("fk_enr_course", inverse.constraintName());
        assertEquals(List.of("course_id"), inverse.columns());
        assertEquals("fk_course", inverse.referencedTable());
        assertEquals(List.of("id"), inverse.referencedColumns());
    }

    @Test
    void elementCollectionCollectionTableEmitsOwnerForeignKey() {
        List<ForeignKeyDefinition> defs =
                ForeignKeyConstraints.resolve(List.of(FkElementOwner.class), factory);

        assertEquals(1, defs.size());
        ForeignKeyDefinition fk = defs.get(0);
        assertEquals("fk_owner_tags", fk.table());
        assertEquals("fk_tags_owner", fk.constraintName());
        assertEquals(List.of("owner_id"), fk.columns());
        assertEquals("fk_element_owner", fk.referencedTable());
        assertEquals(List.of("id"), fk.referencedColumns());
    }

    @Test
    void duplicateTypesAreDeduplicatedBySignature() {
        List<ForeignKeyDefinition> defs = ForeignKeyConstraints.resolve(
                List.of(FkChildConstrained.class, FkChildConstrained.class), factory);

        assertEquals(1, defs.size());
    }

    @Test
    void parentEntityAloneHasNoForeignKeys() {
        assertTrue(ForeignKeyConstraints.resolve(List.of(FkParent.class), factory).isEmpty());
    }
}
