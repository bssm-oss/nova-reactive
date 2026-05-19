package io.nova.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CriteriaTest {

    @Test
    void likeBuildsLikeConditionWithRawPattern() {
        Condition condition = Criteria.like("email", "a%@nova.io");

        assertEquals("email", condition.property());
        assertEquals(ComparisonOperator.LIKE, condition.operator());
        assertEquals("a%@nova.io", condition.value());
    }

    @Test
    void notLikeBuildsNotLikeCondition() {
        Condition condition = Criteria.notLike("email", "%nova.io");

        assertEquals(ComparisonOperator.NOT_LIKE, condition.operator());
        assertEquals("%nova.io", condition.value());
    }

    @Test
    void ilikeBuildsIlikeCondition() {
        Condition condition = Criteria.ilike("email", "%Nova%");

        assertEquals(ComparisonOperator.ILIKE, condition.operator());
        assertEquals("%Nova%", condition.value());
    }

    @Test
    void notIlikeBuildsNotIlikeCondition() {
        Condition condition = Criteria.notIlike("email", "noreply%");

        assertEquals(ComparisonOperator.NOT_ILIKE, condition.operator());
        assertEquals("noreply%", condition.value());
    }

    @Test
    void startsWithAppendsTrailingWildcard() {
        Condition condition = Criteria.startsWith("email", "ada");

        assertEquals(ComparisonOperator.LIKE, condition.operator());
        assertEquals("ada%", condition.value());
    }

    @Test
    void endsWithPrependsLeadingWildcard() {
        Condition condition = Criteria.endsWith("email", "@nova.io");

        assertEquals(ComparisonOperator.LIKE, condition.operator());
        assertEquals("%@nova.io", condition.value());
    }

    @Test
    void containsWrapsValueWithWildcardsOnBothSides() {
        Condition condition = Criteria.contains("email", "nova");

        assertEquals(ComparisonOperator.LIKE, condition.operator());
        assertEquals("%nova%", condition.value());
    }

    @Test
    void startsWithIgnoreCaseUsesIlikeOperator() {
        Condition condition = Criteria.startsWithIgnoreCase("email", "Ada");

        assertEquals(ComparisonOperator.ILIKE, condition.operator());
        assertEquals("Ada%", condition.value());
    }

    @Test
    void endsWithIgnoreCaseUsesIlikeOperator() {
        Condition condition = Criteria.endsWithIgnoreCase("email", "@Nova.IO");

        assertEquals(ComparisonOperator.ILIKE, condition.operator());
        assertEquals("%@Nova.IO", condition.value());
    }

    @Test
    void containsIgnoreCaseUsesIlikeOperator() {
        Condition condition = Criteria.containsIgnoreCase("email", "NoVa");

        assertEquals(ComparisonOperator.ILIKE, condition.operator());
        assertEquals("%NoVa%", condition.value());
    }

    @Test
    void startsWithRejectsNullPrefix() {
        assertThrows(NullPointerException.class, () -> Criteria.startsWith("email", null));
    }

    @Test
    void endsWithRejectsNullSuffix() {
        assertThrows(NullPointerException.class, () -> Criteria.endsWith("email", null));
    }

    @Test
    void containsRejectsNullSubstring() {
        assertThrows(NullPointerException.class, () -> Criteria.contains("email", null));
    }

    @Test
    void startsWithIgnoreCaseRejectsNullPrefix() {
        assertThrows(NullPointerException.class, () -> Criteria.startsWithIgnoreCase("email", null));
    }

    @Test
    void endsWithIgnoreCaseRejectsNullSuffix() {
        assertThrows(NullPointerException.class, () -> Criteria.endsWithIgnoreCase("email", null));
    }

    @Test
    void containsIgnoreCaseRejectsNullSubstring() {
        assertThrows(NullPointerException.class, () -> Criteria.containsIgnoreCase("email", null));
    }

    @Test
    void notLikeRejectsNullValue() {
        assertThrows(NullPointerException.class, () -> Criteria.notLike("email", null));
    }

    @Test
    void ilikeRejectsNullValue() {
        assertThrows(NullPointerException.class, () -> Criteria.ilike("email", null));
    }

    @Test
    void notIlikeRejectsNullValue() {
        assertThrows(NullPointerException.class, () -> Criteria.notIlike("email", null));
    }
}
