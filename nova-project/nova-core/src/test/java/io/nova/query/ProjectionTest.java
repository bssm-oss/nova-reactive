package io.nova.query;

import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionTest {
    record AccountEmail(Long id, String email) {
    }

    @Test
    void ofExposesEntityProjectionAndFields() {
        Projection<SampleAccount, AccountEmail> projection = Projection.of(
                SampleAccount.class,
                AccountEmail.class,
                List.of("id", "email")
        );

        assertSame(SampleAccount.class, projection.entityType());
        assertSame(AccountEmail.class, projection.projectionType());
        assertEquals(List.of("id", "email"), projection.fields());
    }

    @Test
    void ofPreservesFieldOrder() {
        Projection<SampleAccount, AccountEmail> projection = Projection.of(
                SampleAccount.class,
                AccountEmail.class,
                List.of("email", "id")
        );

        assertEquals(List.of("email", "id"), projection.fields());
    }

    @Test
    void ofCopiesInputListSoMutationDoesNotLeak() {
        List<String> source = new ArrayList<>(List.of("id", "email"));
        Projection<SampleAccount, AccountEmail> projection = Projection.of(
                SampleAccount.class,
                AccountEmail.class,
                source
        );

        source.add("active");

        assertEquals(List.of("id", "email"), projection.fields());
        assertNotSame(source, projection.fields());
    }

    @Test
    void fieldsListIsUnmodifiable() {
        Projection<SampleAccount, AccountEmail> projection = Projection.of(
                SampleAccount.class,
                AccountEmail.class,
                List.of("id", "email")
        );

        assertThrows(UnsupportedOperationException.class, () -> projection.fields().add("active"));
    }

    @Test
    void ofRejectsNullEntityType() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Projection.of(null, AccountEmail.class, List.of("id"))
        );

        assertEquals("entityType must not be null", exception.getMessage());
    }

    @Test
    void ofRejectsNullProjectionType() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Projection.of(SampleAccount.class, null, List.of("id"))
        );

        assertEquals("projectionType must not be null", exception.getMessage());
    }

    @Test
    void ofRejectsNullFields() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Projection.of(SampleAccount.class, AccountEmail.class, null)
        );

        assertEquals("fields must not be null", exception.getMessage());
    }

    @Test
    void ofRejectsEmptyFields() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Projection.of(SampleAccount.class, AccountEmail.class, List.of())
        );

        assertEquals("Projection requires at least one field", exception.getMessage());
    }

    @Test
    void ofRejectsNullFieldElement() {
        List<String> fields = new ArrayList<>();
        fields.add("id");
        fields.add(null);

        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Projection.of(SampleAccount.class, AccountEmail.class, fields)
        );

        assertTrue(exception.getMessage().contains("index 1"), "오류 메시지가 index 1을 포함해야 한다: " + exception.getMessage());
    }

    @Test
    void ofRejectsBlankFieldElement() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Projection.of(SampleAccount.class, AccountEmail.class, List.of("id", "  "))
        );

        assertTrue(exception.getMessage().contains("index 1"), "오류 메시지가 index 1을 포함해야 한다: " + exception.getMessage());
    }
}
