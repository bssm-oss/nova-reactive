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
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
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
}
