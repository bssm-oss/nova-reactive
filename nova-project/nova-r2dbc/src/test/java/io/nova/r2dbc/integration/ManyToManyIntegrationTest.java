package io.nova.r2dbc.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import io.nova.core.SqlExecutionListener;
import io.nova.query.NativeQuery;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @ManyToMany}(owning {@code @JoinTable} + inverse {@code mappedBy})가 H2 in-memory R2DBC driver와
 * end-to-end로 동작하는지 검증한다 — join table DDL 생성, save 시 full-replace link 동기화, 양측 2-hop hydration.
 */
class ManyToManyIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        // student, course, 그리고 owning @ManyToMany의 student_course link table을 생성한다.
        schema.create(Student.class, Course.class).block();
    }

    @Test
    void savesLinksAndHydratesBothSides() {
        Course math = support.operations().save(new Course("Math")).block();
        Course art = support.operations().save(new Course("Art")).block();

        Student ada = new Student("ada");
        ada.getCourses().add(math);
        ada.getCourses().add(art);
        Long studentId = support.operations().save(ada).map(Student::getId).block();

        // owning side: Student.courses 2건 hydration.
        StepVerifier.create(support.operations().findById(Student.class, studentId))
                .assertNext(student -> {
                    Set<String> titles = student.getCourses().stream()
                            .map(Course::getTitle).collect(Collectors.toSet());
                    assertEquals(Set.of("Math", "Art"), titles);
                })
                .verifyComplete();

        // inverse side: Course.students를 link table로 hydration.
        StepVerifier.create(support.operations().findById(Course.class, math.getId()))
                .assertNext(course -> {
                    assertEquals(1, course.getStudents().size());
                    assertEquals("ada", course.getStudents().iterator().next().getName());
                })
                .verifyComplete();
    }

    @Test
    void uuidIdsUseJoinColumnStorageForOwningInverseHydrationAndInverseDelete() {
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(UuidStudent.class, UuidCourse.class).block();

        UuidCourse course = support.operations().save(new UuidCourse("Math")).block();
        UuidStudent student = new UuidStudent("ada");
        student.getCourses().add(course);
        UuidStudent saved = support.operations().save(student).block();

        StepVerifier.create(support.operations().findById(UuidStudent.class, saved.getId()))
                .assertNext(loaded -> assertEquals(Set.of("Math"), loaded.getCourses().stream()
                        .map(UuidCourse::getTitle).collect(Collectors.toSet())))
                .verifyComplete();
        StepVerifier.create(support.operations().findById(UuidCourse.class, course.getId()))
                .assertNext(loaded -> assertEquals(Set.of("ada"), loaded.getStudents().stream()
                        .map(UuidStudent::getName).collect(Collectors.toSet())))
                .verifyComplete();

        support.operations().delete(course).block();
        StepVerifier.create(support.operations().findById(UuidStudent.class, saved.getId()))
                .assertNext(loaded -> assertEquals(0, loaded.getCourses().size()))
                .verifyComplete();
    }

    @Test
    void statelessUuidJoinInsertBindsVarcharIdsAndHydratesBothUuidIdsWithoutDuplicateLinks() {
        RecordingSqlListener listener = new RecordingSqlListener();
        H2IntegrationTestSupport uuidSupport = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        SchemaInitializer schema = new SimpleSchemaInitializer(
                uuidSupport.operations(), uuidSupport.metadataFactory(), uuidSupport.dialect());
        schema.create(UuidStudent.class, UuidCourse.class).block();

        UuidCourse course = uuidSupport.operations().save(new UuidCourse("Math")).block();
        UuidStudent student = new UuidStudent("ada");
        student.getCourses().add(course);

        listener.clear();
        UuidStudent saved = uuidSupport.operations().save(student).block();
        SqlStatement joinInsert = listener.lastWrite("uuid_student_course", "insert");
        assertNotNull(joinInsert, "stateless save must insert its join link");
        assertEquals(List.of(saved.getId().toString(), course.getId().toString()), joinInsert.bindings(),
                "single-column UUID join ids must be bound as their varchar storage values");

        StepVerifier.create(uuidSupport.operations().findById(UuidStudent.class, saved.getId()))
                .assertNext(loaded -> {
                    UuidCourse hydratedCourse = loaded.getCourses().iterator().next();
                    assertEquals(course.getId(), hydratedCourse.getId(),
                            "owning hydration must decode the varchar join id to UUID");
                })
                .verifyComplete();
        StepVerifier.create(uuidSupport.operations().findById(UuidCourse.class, course.getId()))
                .assertNext(loaded -> {
                    UuidStudent hydratedStudent = loaded.getStudents().iterator().next();
                    assertEquals(saved.getId(), hydratedStudent.getId(),
                            "inverse hydration must decode the varchar join id to UUID");
                })
                .verifyComplete();

        uuidSupport.operations().save(saved).block();
        assertEquals(1L, uuidSupport.operations().queryNativeOne(
                        NativeQuery.of("select count(*) as c from "
                                + uuidSupport.dialect().quote("uuid_student_course")),
                        row -> row.get("c", Long.class))
                .block(), "re-saving a stateless UUID owner must not leave duplicate links");
    }

    @Test
    void reSaveFullReplacesLinks() {
        Course math = support.operations().save(new Course("Math")).block();
        Course art = support.operations().save(new Course("Art")).block();
        Course bio = support.operations().save(new Course("Bio")).block();

        Student ada = new Student("ada");
        ada.getCourses().add(math);
        ada.getCourses().add(art);
        Long studentId = support.operations().save(ada).map(Student::getId).block();

        // 컬렉션을 [bio]로 교체 후 재저장 → 기존 link 전부 제거되고 bio만 남아야 한다.
        Student loaded = support.operations().findById(Student.class, studentId).block();
        loaded.getCourses().clear();
        loaded.getCourses().add(bio);
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Student.class, studentId))
                .assertNext(student -> {
                    Set<String> titles = student.getCourses().stream()
                            .map(Course::getTitle).collect(Collectors.toSet());
                    assertEquals(Set.of("Bio"), titles);
                })
                .verifyComplete();
    }

    @Test
    void emptyCollectionDeletesAllLinks() {
        Course math = support.operations().save(new Course("Math")).block();
        Student ada = new Student("ada");
        ada.getCourses().add(math);
        Long studentId = support.operations().save(ada).map(Student::getId).block();

        Student loaded = support.operations().findById(Student.class, studentId).block();
        loaded.getCourses().clear();
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Student.class, studentId))
                .assertNext(student -> assertEquals(0, student.getCourses().size()))
                .verifyComplete();
    }

    @Test
    void listOrderByRanksOwningAndInverseBucketsIndependently() {
        Course alpha = support.operations().save(new Course("Alpha")).block();
        Course beta = support.operations().save(new Course("Beta")).block();

        Student ada = new Student("Ada");
        ada.getCourses().add(alpha); // link insertion is the opposite of title DESC.
        ada.getCourses().add(beta);
        Long adaId = support.operations().save(ada).map(Student::getId).block();
        Student zoe = new Student("Zoe");
        zoe.getCourses().add(beta);
        support.operations().save(zoe).block();

        StepVerifier.create(support.operations().findById(Student.class, adaId))
                .assertNext(student -> assertEquals(List.of("Beta", "Alpha"),
                        student.getCourses().stream().map(Course::getTitle).toList()))
                .verifyComplete();
        StepVerifier.create(support.operations().findAll(Student.class, io.nova.query.QuerySpec.empty()).collectList())
                .assertNext(students -> assertEquals(List.of("Beta", "Alpha"),
                        students.stream().filter(student -> student.getName().equals("Ada")).findFirst().orElseThrow()
                                .getCourses().stream().map(Course::getTitle).toList()))
                .verifyComplete();
        StepVerifier.create(support.operations().findById(Course.class, beta.getId()))
                .assertNext(course -> assertEquals(List.of("Zoe", "Ada"),
                        course.getStudents().stream().map(Student::getName).toList()))
                .verifyComplete();
        StepVerifier.create(support.operations().findAll(Course.class, io.nova.query.QuerySpec.empty()).collectList())
                .assertNext(courses -> assertEquals(List.of("Zoe", "Ada"),
                        courses.stream().filter(course -> course.getTitle().equals("Beta")).findFirst().orElseThrow()
                                .getStudents().stream().map(Student::getName).toList()))
                .verifyComplete();
    }

    @Entity
    @Table(name = "student")
    public static class Student {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @ManyToMany
        @JoinTable(name = "student_course",
                joinColumns = @JoinColumn(name = "student_id"),
                inverseJoinColumns = @JoinColumn(name = "course_id"))
        @OrderBy("title DESC, id ASC")
        private List<Course> courses = new ArrayList<>();

        public Student() {
        }

        public Student(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<Course> getCourses() {
            return courses;
        }
    }

    @Entity
    @Table(name = "course")
    public static class Course {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String title;

        @ManyToMany(mappedBy = "courses")
        @OrderBy("name DESC, id ASC")
        private List<Student> students = new ArrayList<>();

        public Course() {
        }

        public Course(String title) {
            this.title = title;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public List<Student> getStudents() {
            return students;
        }
    }

    @Entity
    @Table(name = "uuid_student")
    public static class UuidStudent {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        private String name;

        @ManyToMany
        @JoinTable(name = "uuid_student_course",
                joinColumns = @JoinColumn(name = "student_id"),
                inverseJoinColumns = @JoinColumn(name = "course_id"))
        private Set<UuidCourse> courses = new java.util.LinkedHashSet<>();

        public UuidStudent() {
        }

        public UuidStudent(String name) {
            this.name = name;
        }

        public UUID getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Set<UuidCourse> getCourses() {
            return courses;
        }
    }

    @Entity
    @Table(name = "uuid_course")
    public static class UuidCourse {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        private String title;

        @ManyToMany(mappedBy = "courses")
        private Set<UuidStudent> students = new java.util.LinkedHashSet<>();

        public UuidCourse() {
        }

        public UuidCourse(String title) {
            this.title = title;
        }

        public UUID getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public Set<UuidStudent> getStudents() {
            return students;
        }
    }

    private static final class RecordingSqlListener implements SqlExecutionListener {
        private final List<SqlStatement> statements = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement);
        }

        void clear() {
            statements.clear();
        }

        SqlStatement lastWrite(String table, String operation) {
            SqlStatement found = null;
            for (SqlStatement statement : statements) {
                String sql = statement.sql().toLowerCase(Locale.ROOT);
                if (sql.startsWith(operation) && sql.contains(table)) {
                    found = statement;
                }
            }
            return found;
        }
    }
}
