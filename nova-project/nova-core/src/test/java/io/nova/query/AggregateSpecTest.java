package io.nova.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateSpecTest {
    @Test
    void ofRequiresFirstAggregation() {
        assertThrows(NullPointerException.class, () -> AggregateSpec.of(null));
    }

    @Test
    void ofRejectsNullElementInRest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AggregateSpec.of(Aggregation.count("id"), (Aggregation) null)
        );
    }

    @Test
    void freshSpecHasOnlyAggregations() {
        AggregateSpec spec = AggregateSpec.of(Aggregation.count("id"));

        assertEquals(List.of(Aggregation.count("id")), spec.aggregations());
        assertTrue(spec.groupBy().isEmpty());
        assertNull(spec.where());
        assertNull(spec.having());
        assertNull(spec.sort());
    }

    @Test
    void buildersReturnNewInstances() {
        AggregateSpec base = AggregateSpec.of(Aggregation.sum("total").as("total_sum"));
        AggregateSpec grouped = base.groupBy("active");
        AggregateSpec filtered = grouped.where(Criteria.eq("active", true));
        AggregateSpec withHaving = filtered.having(Criteria.gt("total_sum", 100L));
        AggregateSpec ordered = withHaving.orderBy(Sort.by(Sort.Order.desc("total_sum")));

        assertNotSame(base, grouped);
        assertNotSame(grouped, filtered);
        assertNotSame(filtered, withHaving);
        assertNotSame(withHaving, ordered);

        assertTrue(base.groupBy().isEmpty());
        assertEquals(List.of("active"), grouped.groupBy());
        assertNull(base.where());
        assertNull(grouped.where());
        assertNull(grouped.having());
        assertNull(filtered.having());
        assertNull(withHaving.sort());
    }

    @Test
    void groupByPreservesOrderAndRejectsNullOrBlank() {
        AggregateSpec spec = AggregateSpec.of(Aggregation.count("id")).groupBy("active", "email");

        assertEquals(List.of("active", "email"), spec.groupBy());
        assertThrows(IllegalArgumentException.class,
                () -> AggregateSpec.of(Aggregation.count("id")).groupBy("active", null));
        assertThrows(IllegalArgumentException.class,
                () -> AggregateSpec.of(Aggregation.count("id")).groupBy("active", ""));
        assertThrows(IllegalArgumentException.class,
                () -> AggregateSpec.of(Aggregation.count("id")).groupBy("active", "   "));
    }

    @Test
    void whereHavingAndOrderByRejectNull() {
        AggregateSpec spec = AggregateSpec.of(Aggregation.count("id"));

        assertThrows(NullPointerException.class, () -> spec.where(null));
        assertThrows(NullPointerException.class, () -> spec.having(null));
        assertThrows(NullPointerException.class, () -> spec.orderBy(null));
    }
}
