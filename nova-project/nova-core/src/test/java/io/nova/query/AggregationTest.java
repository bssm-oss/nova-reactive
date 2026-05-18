package io.nova.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AggregationTest {
    @Test
    void factoriesCarryFunctionAndProperty() {
        assertEquals(AggregateFunction.COUNT, Aggregation.count("id").function());
        assertEquals(AggregateFunction.COUNT_DISTINCT, Aggregation.countDistinct("email").function());
        assertEquals(AggregateFunction.SUM, Aggregation.sum("total").function());
        assertEquals(AggregateFunction.AVG, Aggregation.avg("total").function());
        assertEquals(AggregateFunction.MIN, Aggregation.min("total").function());
        assertEquals(AggregateFunction.MAX, Aggregation.max("total").function());
        assertEquals("email", Aggregation.countDistinct("email").property());
    }

    @Test
    void aliasDefaultsToNullAndResolvesToFunctionName() {
        Aggregation count = Aggregation.count("id");
        assertNull(count.alias());
        assertEquals("count", count.resolvedAlias());
        assertEquals("count_distinct", Aggregation.countDistinct("email").resolvedAlias());
        assertEquals("sum", Aggregation.sum("total").resolvedAlias());
    }

    @Test
    void asReturnsImmutableCopyWithAlias() {
        Aggregation base = Aggregation.sum("total");
        Aggregation aliased = base.as("total_sum");

        assertNotSame(base, aliased);
        assertNull(base.alias());
        assertEquals("total_sum", aliased.alias());
        assertEquals("total_sum", aliased.resolvedAlias());
        assertEquals(AggregateFunction.SUM, aliased.function());
        assertEquals("total", aliased.property());
    }

    @Test
    void rejectsNullFunctionPropertyOrAlias() {
        assertThrows(NullPointerException.class, () -> new Aggregation(null, "id", null));
        assertThrows(NullPointerException.class, () -> new Aggregation(AggregateFunction.COUNT, null, null));
        assertThrows(NullPointerException.class, () -> Aggregation.count("id").as(null));
    }

    @Test
    void rejectsBlankPropertyOrAlias() {
        assertThrows(IllegalArgumentException.class, () -> Aggregation.count(""));
        assertThrows(IllegalArgumentException.class, () -> Aggregation.count("  "));
        assertThrows(IllegalArgumentException.class, () -> Aggregation.count("id").as(""));
        assertThrows(IllegalArgumentException.class, () -> Aggregation.count("id").as("   "));
    }
}
