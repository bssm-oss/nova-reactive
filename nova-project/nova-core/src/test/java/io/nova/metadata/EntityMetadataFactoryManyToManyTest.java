package io.nova.metadata;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ManyToMany}(owning {@code @JoinTable} / inverse {@code mappedBy}) 메타데이터 추출과 거부 규칙을 보호한다.
 */
class EntityMetadataFactoryManyToManyTest {
    private final DefaultNamingStrategy naming = new DefaultNamingStrategy();
    private final EntityMetadataFactory factory = new EntityMetadataFactory(naming);

    @Test
    void owningSideResolvesJoinTableMapping() {
        EntityMetadata<Student> metadata = factory.getEntityMetadata(Student.class);
        PersistentProperty courses = metadata.findProperty("courses").orElseThrow();

        assertTrue(courses.manyToMany());
        ManyToManyInfo info = courses.manyToManyInfo();
        assertTrue(info.owning());
        assertEquals(Course.class, info.targetType());
        assertEquals("student_course", info.joinTableName());
        assertEquals("student_id", info.ownerForeignKeyColumn());
        assertEquals("course_id", info.targetForeignKeyColumn());
        assertTrue(info.usesSet());

        // marker라 컬럼 매핑 뷰에서 제외된다.
        assertFalse(metadata.columnMappedProperties().stream().anyMatch(p -> p.propertyName().equals("courses")));
        assertTrue(metadata.hasRelationProperties());
        assertEquals(1, metadata.manyToManyProperties().size());
    }

    @Test
    void inverseSideSwapsColumnsFromOwning() {
        EntityMetadata<Course> metadata = factory.getEntityMetadata(Course.class);
        PersistentProperty students = metadata.findProperty("students").orElseThrow();

        ManyToManyInfo info = students.manyToManyInfo();
        assertFalse(info.owning());
        assertEquals(Student.class, info.targetType());
        assertEquals("student_course", info.joinTableName());
        // owner = this inverse entity(Course) → ownerFk는 course_id, targetFk는 student_id (owning 대비 swap).
        assertEquals("course_id", info.ownerForeignKeyColumn());
        assertEquals("student_id", info.targetForeignKeyColumn());
        assertEquals("courses", info.mappedBy());
    }

    @Test
    void defaultsNamesWhenJoinTableOmitted() {
        EntityMetadata<Tag> metadata = factory.getEntityMetadata(Tag.class);
        ManyToManyInfo info = metadata.findProperty("articles").orElseThrow().manyToManyInfo();
        String tagTable = naming.tableName(Tag.class);
        String articleTable = naming.tableName(Article.class);
        assertEquals(tagTable + "_" + articleTable, info.joinTableName());
        assertEquals(naming.columnName("Tag") + "_id", info.ownerForeignKeyColumn());
        assertEquals(naming.columnName("Article") + "_id", info.targetForeignKeyColumn());
        assertFalse(info.usesSet(), "List는 usesSet=false");
    }

    @Test
    void honorsOwningSideCascade() {
        EntityMetadata<CascadingManyToMany> metadata = factory.getEntityMetadata(CascadingManyToMany.class);
        ManyToManyInfo info = metadata.findProperty("courses").orElseThrow().manyToManyInfo();
        // owning @ManyToMany(cascade=ALL) → PERSIST/MERGE 모두 전파.
        assertTrue(info.owning());
        assertTrue(info.cascadePersist());
        assertTrue(info.cascadeMerge());
    }

    @Test
    void rejectsCascadeOnInverseSide() {
        // cascade는 owning side(@JoinTable)에서만 의미가 있으므로 mappedBy side 선언은 fail-fast로 거부한다.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(InverseCascadeTarget.class));
        assertTrue(error.getMessage().contains("owning side"));
    }

    @Test
    void rejectsRawCollection() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(RawManyToMany.class));
    }

    @Test
    void rejectsNonCollectionField() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(NonCollectionManyToMany.class));
    }

    @Test
    void alignsCompositeJoinColumnsByReferencedColumnNameNotDeclarationOrder() {
        // inverseJoinColumns를 참조 @Id 컬럼(pa, pb)과 역순(pb, pa)으로 선언해도, referencedColumnName 매칭으로
        // 참조 컴포넌트 순서(pa, pb)에 맞춰 정렬되어야 한다(positional이 아니라 이름 매칭).
        EntityMetadata<Machine> metadata = factory.getEntityMetadata(Machine.class);
        ManyToManyInfo info = metadata.findProperty("parts").orElseThrow().manyToManyInfo();
        assertTrue(info.composite());
        assertEquals(List.of("fk_a", "fk_b"),
                info.targetForeignKeyColumns().stream().map(ManyToManyInfo.JoinColumnRef::columnName).toList());
        assertEquals(List.of("pa", "pb"),
                info.targetForeignKeyColumns().stream().map(ManyToManyInfo.JoinColumnRef::referencedColumnName).toList());
    }

    @Test
    void acceptsCompositeKeyedOwnerWithMultiColumnJoin() {
        // 복합키(@EmbeddedId) owner + 단일키 target: owner FK는 참조 @Id 컴포넌트마다 컬럼 1개(a, b), target FK는 1개.
        EntityMetadata<CompositeOwner> metadata = factory.getEntityMetadata(CompositeOwner.class);
        ManyToManyInfo info = metadata.findProperty("courses").orElseThrow().manyToManyInfo();
        assertTrue(info.owning());
        assertTrue(info.composite());
        assertEquals(2, info.ownerForeignKeyColumns().size());
        assertEquals(List.of("composite_owner_a", "composite_owner_b"),
                info.ownerForeignKeyColumns().stream().map(ManyToManyInfo.JoinColumnRef::columnName).toList());
        assertEquals(List.of("a", "b"),
                info.ownerForeignKeyColumns().stream().map(ManyToManyInfo.JoinColumnRef::referencedColumnName).toList());
        assertEquals(1, info.targetForeignKeyColumns().size());
        assertEquals("course_id", info.targetForeignKeyColumn());
    }

    @Test
    void rejectsCombiningManyToManyWithManyToOne() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(ConflictingRelations.class));
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "student")
    static class Student {
        @Id
        Long id;

        @ManyToMany
        @JoinTable(name = "student_course",
                joinColumns = @JoinColumn(name = "student_id"),
                inverseJoinColumns = @JoinColumn(name = "course_id"))
        Set<Course> courses;
    }

    @Entity
    @Table(name = "course")
    static class Course {
        @Id
        Long id;

        @ManyToMany(mappedBy = "courses")
        Set<Student> students;
    }

    @Entity
    @Table(name = "tag")
    static class Tag {
        @Id
        Long id;

        @ManyToMany
        List<Article> articles;
    }

    @Entity
    @Table(name = "article")
    static class Article {
        @Id
        Long id;
    }

    @Entity
    @Table(name = "cascade_m2m")
    static class CascadingManyToMany {
        @Id
        Long id;

        @ManyToMany(cascade = CascadeType.ALL)
        List<Course> courses;
    }

    @Entity
    @Table(name = "inv_cascade_owner")
    static class InverseCascadeOwner {
        @Id
        Long id;

        @ManyToMany
        @JoinTable(name = "inv_cascade_link",
                joinColumns = @JoinColumn(name = "owner_id"),
                inverseJoinColumns = @JoinColumn(name = "target_id"))
        List<InverseCascadeTarget> targets;
    }

    @Entity
    @Table(name = "inv_cascade_target")
    static class InverseCascadeTarget {
        @Id
        Long id;

        @ManyToMany(mappedBy = "targets", cascade = CascadeType.ALL)
        List<InverseCascadeOwner> owners;
    }

    @Entity
    @Table(name = "raw_m2m")
    static class RawManyToMany {
        @Id
        Long id;

        @ManyToMany
        @SuppressWarnings("rawtypes")
        List courses;
    }

    @Entity
    @Table(name = "noncoll_m2m")
    static class NonCollectionManyToMany {
        @Id
        Long id;

        @ManyToMany
        Course course;
    }

    @Embeddable
    static class CompositeId {
        Long a;
        Long b;
    }

    @Entity
    @Table(name = "composite_owner")
    static class CompositeOwner {
        @EmbeddedId
        CompositeId id;

        @ManyToMany
        List<Course> courses;
    }

    @Entity
    @Table(name = "conflict_m2m")
    static class ConflictingRelations {
        @Id
        Long id;

        @ManyToMany
        @jakarta.persistence.ManyToOne
        Course course;
    }

    @Embeddable
    static class PartId {
        @jakarta.persistence.Column(name = "pa")
        Long a;
        @jakarta.persistence.Column(name = "pb")
        Long b;
    }

    @Entity
    @Table(name = "part")
    static class Part {
        @EmbeddedId
        PartId id;
    }

    @Entity
    @Table(name = "machine")
    static class Machine {
        @Id
        Long id;

        // inverseJoinColumns를 참조 컬럼(pa, pb) 역순으로 선언하고 referencedColumnName으로 매칭시킨다.
        @ManyToMany
        @JoinTable(name = "machine_part",
                joinColumns = @JoinColumn(name = "machine_id"),
                inverseJoinColumns = {
                        @JoinColumn(name = "fk_b", referencedColumnName = "pb"),
                        @JoinColumn(name = "fk_a", referencedColumnName = "pa")})
        List<Part> parts;
    }
}
