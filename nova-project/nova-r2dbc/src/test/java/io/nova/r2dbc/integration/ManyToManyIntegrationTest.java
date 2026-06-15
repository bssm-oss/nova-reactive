package io.nova.r2dbc.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        private Set<Course> courses = new LinkedHashSet<>();

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

        public Set<Course> getCourses() {
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
        private Set<Student> students = new LinkedHashSet<>();

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

        public Set<Student> getStudents() {
            return students;
        }
    }
}
