package io.nova.cache;

import io.nova.query.Criteria;
import io.nova.query.LockMode;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QuerySpecCacheKey} 정규화가 결정적(같은 스펙 → 같은 키)이고, 의미가 다른 스펙(값·타입·정렬·페이지)은
 * 다른 키를 만드는지 검증한다. 캐시 키 충돌은 stale이 아니라 <b>잘못된 결과</b>를 서빙하므로 분리가 핵심이다.
 */
class QuerySpecCacheKeyTest {

    static class Foo {
    }

    static class Bar {
    }

    @Test
    void sameSpecProducesSameKey() {
        QuerySpec a = QuerySpec.empty().where(Criteria.eq("name", "alpha"));
        QuerySpec b = QuerySpec.empty().where(Criteria.eq("name", "alpha"));
        assertEquals(QuerySpecCacheKey.of(Foo.class, a), QuerySpecCacheKey.of(Foo.class, b));
    }

    @Test
    void differentBoundValueProducesDifferentKey() {
        QuerySpec a = QuerySpec.empty().where(Criteria.eq("name", "alpha"));
        QuerySpec b = QuerySpec.empty().where(Criteria.eq("name", "beta"));
        assertNotEquals(QuerySpecCacheKey.of(Foo.class, a), QuerySpecCacheKey.of(Foo.class, b));
    }

    @Test
    void sameTextValueOfDifferentTypeProducesDifferentKey() {
        // Integer 5 와 Long 5 는 텍스트가 "5"로 같지만 서로 다른 바인딩이므로 키가 갈라져야 한다.
        QuerySpec intSpec = QuerySpec.empty().where(Criteria.eq("qty", 5));
        QuerySpec longSpec = QuerySpec.empty().where(Criteria.eq("qty", 5L));
        assertNotEquals(QuerySpecCacheKey.of(Foo.class, intSpec), QuerySpecCacheKey.of(Foo.class, longSpec));
    }

    @Test
    void differentEntityTypeProducesDifferentKey() {
        QuerySpec spec = QuerySpec.empty().where(Criteria.eq("name", "alpha"));
        assertNotEquals(QuerySpecCacheKey.of(Foo.class, spec), QuerySpecCacheKey.of(Bar.class, spec));
    }

    @Test
    void differentSortProducesDifferentKey() {
        QuerySpec asc = QuerySpec.empty().orderBy(Sort.by(Sort.Order.asc("name")));
        QuerySpec desc = QuerySpec.empty().orderBy(Sort.by(Sort.Order.desc("name")));
        assertNotEquals(QuerySpecCacheKey.of(Foo.class, asc), QuerySpecCacheKey.of(Foo.class, desc));
    }

    @Test
    void differentPageableProducesDifferentKey() {
        QuerySpec p1 = QuerySpec.empty().page(Pageable.of(10, 0));
        QuerySpec p2 = QuerySpec.empty().page(Pageable.of(10, 10));
        assertNotEquals(QuerySpecCacheKey.of(Foo.class, p1), QuerySpecCacheKey.of(Foo.class, p2));
    }

    @Test
    void lockModeIsPartOfKey() {
        QuerySpec none = QuerySpec.empty().where(Criteria.eq("name", "alpha"));
        QuerySpec locked = none.lockMode(LockMode.FOR_UPDATE);
        assertNotEquals(QuerySpecCacheKey.of(Foo.class, none), QuerySpecCacheKey.of(Foo.class, locked));
    }

    @Test
    void emptySpecKeyIsStableAndIncludesEntityType() {
        String k = QuerySpecCacheKey.of(Foo.class, QuerySpec.empty());
        assertEquals(k, QuerySpecCacheKey.of(Foo.class, QuerySpec.empty()));
        assertTrue(k.startsWith(Foo.class.getName()), "키는 엔티티 타입으로 시작해 타입별 분리를 보조한다");
    }

    @Test
    void compoundAndNegationPredicatesAreDistinguished() {
        QuerySpec and = QuerySpec.empty()
                .where(Criteria.and(Criteria.eq("a", 1), Criteria.eq("b", 2)));
        QuerySpec or = QuerySpec.empty()
                .where(Criteria.or(Criteria.eq("a", 1), Criteria.eq("b", 2)));
        assertNotEquals(QuerySpecCacheKey.of(Foo.class, and), QuerySpecCacheKey.of(Foo.class, or));
    }
}
