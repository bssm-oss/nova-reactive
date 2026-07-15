package io.nova.spring.data.derived;

import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.Slice;
import io.nova.query.Sort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivedQueryParserTest {

    static final class Account {
        Long id;
        String email;
        String emailAddress;
        boolean active;
        Instant createdAt;
        int loginCount;
    }

    interface AccountRepository {
        Mono<Account> findByEmail(String email);

        Flux<Account> findByEmailAddress(String emailAddress);

        Flux<Account> findByActiveTrue();

        Flux<Account> findByActiveFalse();

        Mono<Long> countByActive(boolean active);

        Mono<Boolean> existsByEmail(String email);

        Mono<Long> deleteByEmail(String email);

        Mono<Long> removeByEmail(String email);

        Flux<Account> findByLoginCountGreaterThan(int loginCount);

        Flux<Account> findByLoginCountLessThanEqual(int loginCount);

        Flux<Account> findByEmailLike(String pattern);

        Flux<Account> findByEmailStartingWith(String prefix);

        Flux<Account> findByEmailContaining(String chunk);

        Flux<Account> findByEmailIn(Iterable<String> emails);

        Flux<Account> findByEmailNotIn(Iterable<String> emails);

        Flux<Account> findByLoginCountBetween(int low, int high);

        Flux<Account> findByEmailIsNull();

        Flux<Account> findByEmailIsNotNull();

        Flux<Account> findByEmailNot(String email);

        Flux<Account> findByEmailAndActiveTrue(String email);

        Flux<Account> findByEmailOrEmailAddress(String email, String emailAddress);

        Flux<Account> findByEmailOrderByLoginCountDesc(String email);

        Flux<Account> findByEmailOrderByLoginCountDescAndCreatedAtAsc(String email);

        Flux<Account> findByCreatedAtAfter(Instant after);

        Flux<Account> findByCreatedAtBefore(Instant before);

        Mono<Account> findFirstByActiveTrueOrderByCreatedAtDesc();

        Mono<Account> findOneByEmail(String email);

        Mono<Account> findTopByEmail(String email);

        Flux<Account> findTop2ByActiveTrue();

        Flux<Account> findFirst3ByActiveTrueOrderByLoginCountDesc();

        Flux<Account> findTop0ByActiveTrue();

        Mono<Account> findTop3ByActiveTrue();

        Flux<Account> findByEmailIgnoreCase(String email);

        Flux<Account> findByEmailContainingIgnoreCase(String chunk);

        Flux<Account> findByEmailNotIgnoreCase(String email);

        Flux<Account> findByLoginCountGreaterThanIgnoreCase(int loginCount);

        // --- T2b: Pageable / Page / Slice ---
        Flux<Account> findByActiveTrue(Pageable pageable);

        Mono<Page<Account>> findByActive(boolean active, Pageable pageable);

        Mono<Slice<Account>> findByEmailContaining(String chunk, Pageable pageable);

        Mono<Account> findByEmail(String email, Pageable pageable);

        Mono<Page<Account>> countByActive(boolean active, Pageable pageable);

        Flux<Account> findByEmail(Pageable pageable, String email);

        Flux<Account> findFirst2ByActiveTrue(Pageable pageable);
    }

    private final DerivedQueryParser parser = new DerivedQueryParser(Account.class);

    private Method method(String name, Class<?>... params) {
        try {
            return AccountRepository.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private DerivedQuery parse(String name, Class<?>... params) {
        Optional<DerivedQuery> q = parser.tryParse(method(name, params));
        assertTrue(q.isPresent(), () -> "expected '" + name + "' to parse");
        return q.get();
    }

    @Nested
    @DisplayName("subject prefix 매핑")
    class SubjectMapping {

        @Test
        @DisplayName("findBy + Flux → FIND_ALL")
        void findByFluxStaysAll() {
            assertSame(Subject.FIND_ALL, parse("findByEmailAddress", String.class).subject());
        }

        @Test
        @DisplayName("findBy + Mono → FIND_ONE (DB-side LIMIT 1)")
        void findByMonoPromotesToFindOne() {
            // Mono<Account> findByEmail(String) — 반환 타입이 Mono이므로 자동으로 FIND_ONE 으로 승격된다.
            assertSame(Subject.FIND_ONE, parse("findByEmail", String.class).subject());
        }

        @Test
        void countBy() {
            assertSame(Subject.COUNT, parse("countByActive", boolean.class).subject());
        }

        @Test
        void existsBy() {
            assertSame(Subject.EXISTS, parse("existsByEmail", String.class).subject());
        }

        @Test
        void deleteBy() {
            assertSame(Subject.DELETE, parse("deleteByEmail", String.class).subject());
        }

        @Test
        void removeBy() {
            assertSame(Subject.DELETE, parse("removeByEmail", String.class).subject());
        }

        @Test
        void findFirstBy() {
            assertSame(Subject.FIND_ONE, parse("findFirstByActiveTrueOrderByCreatedAtDesc").subject());
        }

        @Test
        void findOneBy() {
            assertSame(Subject.FIND_ONE, parse("findOneByEmail", String.class).subject());
        }

        @Test
        void findTopBy() {
            assertSame(Subject.FIND_ONE, parse("findTopByEmail", String.class).subject());
        }

        @Test
        @DisplayName("findTop<N>By(N>=2)는 FIND_ALL을 유지하고 limit에 N을 싣는다")
        void findTopNStaysFindAllWithLimit() {
            DerivedQuery q = parse("findTop2ByActiveTrue");
            assertSame(Subject.FIND_ALL, q.subject());
            assertEquals(2, q.limit());
        }

        @Test
        @DisplayName("findFirst<N>By(N>=2)도 findTop<N>By와 동일하게 취급된다")
        void findFirstNStaysFindAllWithLimit() {
            DerivedQuery q = parse("findFirst3ByActiveTrueOrderByLoginCountDesc");
            assertSame(Subject.FIND_ALL, q.subject());
            assertEquals(3, q.limit());
            assertEquals(1, q.orderings().size());
        }

        @Test
        @DisplayName("findTop0By는 의미가 없으므로 즉시 fail")
        void findTopZeroRejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(method("findTop0ByActiveTrue")));
            assertTrue(ex.getMessage().contains("top 0"), () -> "unexpected message: " + ex.getMessage());
        }

        @Test
        @DisplayName("findTop<N>By(N>=2)에 Mono 반환 타입은 모순이므로 즉시 fail")
        void findTopNWithMonoReturnTypeRejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(method("findTop3ByActiveTrue")));
            assertTrue(ex.getMessage().contains("Mono"), () -> "unexpected message: " + ex.getMessage());
        }

        @Test
        @DisplayName("int 범위를 넘는 findTop<N>은 raw NumberFormatException이 아닌 IllegalArgumentException")
        void findTopHugeNWrapsNumberFormat() {
            interface Repo {
                Flux<Account> findTop99999999999ByActiveTrue();
            }
            Method m;
            try {
                m = Repo.class.getMethod("findTop99999999999ByActiveTrue");
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(m));
            assertTrue(ex.getMessage().contains("valid int"), () -> "unexpected message: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("keyword 매핑")
    class KeywordMapping {

        @Test
        @DisplayName("default(suffix 없음) → EQ 한 인자")
        void defaultEq() {
            DerivedQuery q = parse("findByEmail", String.class);
            Part p = q.orGroups().get(0).get(0);
            assertEquals("email", p.propertyName());
            assertSame(Keyword.EQ, p.keyword());
            assertEquals(1, q.expectedArgs());
        }

        @Test
        @DisplayName("longest suffix가 먼저 매칭된다 — GreaterThanEqual ≠ GreaterThan")
        void longestSuffixWins() {
            assertSame(Keyword.GT, parse("findByLoginCountGreaterThan", int.class).orGroups().get(0).get(0).keyword());
            assertSame(Keyword.LTE, parse("findByLoginCountLessThanEqual", int.class).orGroups().get(0).get(0).keyword());
        }

        @Test
        @DisplayName("True/False/IsNull/IsNotNull 은 인자 0개")
        void zeroArgKeywords() {
            assertEquals(0, parse("findByActiveTrue").expectedArgs());
            assertEquals(0, parse("findByActiveFalse").expectedArgs());
            assertEquals(0, parse("findByEmailIsNull").expectedArgs());
            assertEquals(0, parse("findByEmailIsNotNull").expectedArgs());
        }

        @Test
        @DisplayName("Between 은 인자 2개")
        void betweenConsumesTwoArgs() {
            DerivedQuery q = parse("findByLoginCountBetween", int.class, int.class);
            assertSame(Keyword.BETWEEN, q.orGroups().get(0).get(0).keyword());
            assertEquals(2, q.expectedArgs());
        }

        @Test
        @DisplayName("Like/StartingWith/Containing/In/NotIn 도 인식한다")
        void miscKeywords() {
            assertSame(Keyword.LIKE, parse("findByEmailLike", String.class).orGroups().get(0).get(0).keyword());
            assertSame(Keyword.STARTING_WITH, parse("findByEmailStartingWith", String.class).orGroups().get(0).get(0).keyword());
            assertSame(Keyword.CONTAINING, parse("findByEmailContaining", String.class).orGroups().get(0).get(0).keyword());
            assertSame(Keyword.IN, parse("findByEmailIn", Iterable.class).orGroups().get(0).get(0).keyword());
            assertSame(Keyword.NOT_IN, parse("findByEmailNotIn", Iterable.class).orGroups().get(0).get(0).keyword());
            assertSame(Keyword.NOT, parse("findByEmailNot", String.class).orGroups().get(0).get(0).keyword());
        }

        @Test
        @DisplayName("After/Before alias도 받는다")
        void temporalAliases() {
            assertSame(Keyword.GT, parse("findByCreatedAtAfter", Instant.class).orGroups().get(0).get(0).keyword());
            assertSame(Keyword.LT, parse("findByCreatedAtBefore", Instant.class).orGroups().get(0).get(0).keyword());
        }

        @Test
        @DisplayName("IgnoreCase는 keyword는 그대로 두고 Part.ignoreCase() 플래그만 세운다")
        void ignoreCaseSetsFlagWithoutChangingKeyword() {
            Part eqPart = parse("findByEmailIgnoreCase", String.class).orGroups().get(0).get(0);
            assertSame(Keyword.EQ, eqPart.keyword());
            assertTrue(eqPart.ignoreCase());

            Part containingPart = parse("findByEmailContainingIgnoreCase", String.class).orGroups().get(0).get(0);
            assertSame(Keyword.CONTAINING, containingPart.keyword());
            assertTrue(containingPart.ignoreCase());

            Part notPart = parse("findByEmailNotIgnoreCase", String.class).orGroups().get(0).get(0);
            assertSame(Keyword.NOT, notPart.keyword());
            assertTrue(notPart.ignoreCase());
        }

        @Test
        @DisplayName("IgnoreCase가 없는 part는 ignoreCase()가 false")
        void withoutIgnoreCaseFlagIsFalse() {
            Part p = parse("findByEmail", String.class).orGroups().get(0).get(0);
            assertTrue(!p.ignoreCase());
        }

        @Test
        @DisplayName("문자열 비교가 아닌 keyword와 IgnoreCase 조합은 즉시 fail")
        void ignoreCaseOnNonStringKeywordRejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(method("findByLoginCountGreaterThanIgnoreCase", int.class)));
            assertTrue(ex.getMessage().contains("IgnoreCase"), () -> "unexpected message: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Pageable / Page / Slice (T2b)")
    class Paging {

        @Test
        @DisplayName("Flux + Pageable → FLUX paging, subject는 FIND_ALL 유지, keyword 인자는 Pageable 제외")
        void fluxWithPageable() {
            DerivedQuery q = parse("findByActiveTrue", Pageable.class);
            assertSame(Subject.FIND_ALL, q.subject());
            assertSame(PagingResult.FLUX, q.pagingResult());
            assertTrue(q.hasPageable());
            assertEquals(0, q.pageableArgIndex());
            assertEquals(0, q.expectedArgs(), "ActiveTrue는 인자 0개, Pageable은 keyword 인자 아님");
        }

        @Test
        @DisplayName("Mono<Page<T>> → PAGE paging, keyword 인자만 카운트")
        void monoPage() {
            DerivedQuery q = parse("findByActive", boolean.class, Pageable.class);
            assertSame(Subject.FIND_ALL, q.subject());
            assertSame(PagingResult.PAGE, q.pagingResult());
            assertEquals(1, q.pageableArgIndex());
            assertEquals(1, q.expectedArgs());
        }

        @Test
        @DisplayName("Mono<Slice<T>> → SLICE paging")
        void monoSlice() {
            DerivedQuery q = parse("findByEmailContaining", String.class, Pageable.class);
            assertSame(Subject.FIND_ALL, q.subject());
            assertSame(PagingResult.SLICE, q.pagingResult());
            assertEquals(1, q.pageableArgIndex());
        }

        @Test
        @DisplayName("Mono<Page<T>> 인데 Pageable 파라미터가 없으면 fail-fast")
        void pageWithoutPageableRejected() {
            interface Repo {
                Mono<Page<Account>> findByActiveTrue();
            }
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(repoMethod(Repo.class, "findByActiveTrue")));
            assertTrue(ex.getMessage().contains("no Pageable"), () -> "unexpected: " + ex.getMessage());
        }

        @Test
        @DisplayName("단건 Mono<T> + Pageable 조합은 parse-time fail-fast (반환타입 non-pageable)")
        void singleMonoWithPageableRejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(method("findByEmail", String.class, Pageable.class)));
            assertTrue(ex.getMessage().contains("not") && ex.getMessage().contains("pageable"),
                    () -> "unexpected: " + ex.getMessage());
        }

        @Test
        @DisplayName("Mono 래핑 없는 raw Page<T> 반환 + Pageable은 parse-time fail-fast (dispatch까지 미루지 않음)")
        void nonMonoPageReturnWithPageableRejected() {
            interface Repo {
                Page<Account> findByActive(boolean active, Pageable pageable);
            }
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(repoMethod(Repo.class, "findByActive", boolean.class, Pageable.class)));
            assertTrue(ex.getMessage().contains("not") && ex.getMessage().contains("pageable"),
                    () -> "unexpected: " + ex.getMessage());
        }

        @Test
        @DisplayName("non-reactive 반환(List<T>) + Pageable도 parse-time fail-fast")
        void nonReactiveReturnWithPageableRejected() {
            interface Repo {
                List<Account> findByActiveTrue(Pageable pageable);
            }
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(repoMethod(Repo.class, "findByActiveTrue", Pageable.class)));
            assertTrue(ex.getMessage().contains("not") && ex.getMessage().contains("pageable"),
                    () -> "unexpected: " + ex.getMessage());
        }

        @Test
        @DisplayName("count/exists/delete + Pageable은 fail-fast")
        void countWithPageableRejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(method("countByActive", boolean.class, Pageable.class)));
            assertTrue(ex.getMessage().contains("not a plain find query"),
                    () -> "unexpected: " + ex.getMessage());
        }

        @Test
        @DisplayName("Pageable이 마지막 파라미터가 아니면 fail-fast")
        void pageableNotLastRejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(method("findByEmail", Pageable.class, String.class)));
            assertTrue(ex.getMessage().contains("last"), () -> "unexpected: " + ex.getMessage());
        }

        @Test
        @DisplayName("findFirst<N> + Pageable 조합은 fail-fast — 개수 상한과 페이지가 충돌")
        void topNWithPageableRejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(method("findFirst2ByActiveTrue", Pageable.class)));
            assertTrue(ex.getMessage().contains("not a plain find query"),
                    () -> "unexpected: " + ex.getMessage());
        }

        private Method repoMethod(Class<?> repo, String name, Class<?>... params) {
            try {
                return repo.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Nested
    @DisplayName("property 매칭")
    class PropertyMatching {

        @Test
        @DisplayName("긴 property 이름이 짧은 것보다 먼저 매칭된다 — Email vs EmailAddress")
        void longerPropertyWins() {
            // "EmailAddress"는 "Email" + "Address"로 잘못 잘리면 안 된다.
            DerivedQuery q = parse("findByEmailAddress", String.class);
            assertEquals("emailAddress", q.orGroups().get(0).get(0).propertyName());
        }

        @Test
        @DisplayName("등록되지 않은 property는 IllegalArgumentException")
        void unknownPropertyThrows() {
            interface BadRepo {
                Flux<Account> findByUnknownThing(String x);
            }
            Method m;
            try {
                m = BadRepo.class.getMethod("findByUnknownThing", String.class);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(m));
            assertTrue(ex.getMessage().contains("unknown property"),
                    () -> "unexpected message: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("connector / order")
    class ConnectorAndOrder {

        @Test
        @DisplayName("And 는 단일 OR-group 안에 두 part로 들어간다")
        void andProducesConjunction() {
            DerivedQuery q = parse("findByEmailAndActiveTrue", String.class);
            assertEquals(1, q.orGroups().size());
            List<Part> conjunction = q.orGroups().get(0);
            assertEquals(2, conjunction.size());
            assertEquals("email", conjunction.get(0).propertyName());
            assertSame(Keyword.EQ, conjunction.get(0).keyword());
            assertEquals("active", conjunction.get(1).propertyName());
            assertSame(Keyword.IS_TRUE, conjunction.get(1).keyword());
            assertEquals(1, q.expectedArgs());
        }

        @Test
        @DisplayName("Or 는 OR-group 두 개를 만든다")
        void orProducesDisjunction() {
            DerivedQuery q = parse("findByEmailOrEmailAddress", String.class, String.class);
            assertEquals(2, q.orGroups().size());
            assertEquals(1, q.orGroups().get(0).size());
            assertEquals(1, q.orGroups().get(1).size());
            assertEquals("email", q.orGroups().get(0).get(0).propertyName());
            assertEquals("emailAddress", q.orGroups().get(1).get(0).propertyName());
        }

        @Test
        @DisplayName("OrderBy 절은 sort orderings로 분리된다")
        void orderByExtracted() {
            DerivedQuery q = parse("findByEmailOrderByLoginCountDesc", String.class);
            assertEquals(1, q.orderings().size());
            assertEquals("loginCount", q.orderings().get(0).propertyName());
            assertSame(Sort.Direction.DESC, q.orderings().get(0).direction());
        }

        @Test
        @DisplayName("OrderBy 절은 And 로 여러 개를 나열할 수 있다 — 첫 항목은 Asc default")
        void multipleOrderByEntries() {
            DerivedQuery q = parse("findByEmailOrderByLoginCountDescAndCreatedAtAsc", String.class);
            assertEquals(2, q.orderings().size());
            assertEquals("loginCount", q.orderings().get(0).propertyName());
            assertSame(Sort.Direction.DESC, q.orderings().get(0).direction());
            assertEquals("createdAt", q.orderings().get(1).propertyName());
            assertSame(Sort.Direction.ASC, q.orderings().get(1).direction());
        }
    }

    @Nested
    @DisplayName("실패 모드")
    class FailureModes {

        @Test
        @DisplayName("known prefix가 없으면 Optional.empty — IllegalArgument 아님")
        void unknownPrefixReturnsEmpty() {
            interface Repo {
                Flux<Account> randomMethod();
            }
            Method m;
            try {
                m = Repo.class.getMethod("randomMethod");
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            assertTrue(parser.tryParse(m).isEmpty());
        }

        @Test
        @DisplayName("parameter 개수가 keyword 합과 다르면 명시적인 메시지로 fail")
        void parameterCountMismatchThrows() {
            interface BadRepo {
                Flux<Account> findByEmail(String email, String extra);
            }
            Method m;
            try {
                m = BadRepo.class.getMethod("findByEmail", String.class, String.class);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.tryParse(m));
            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().contains("expects 1"),
                    () -> "unexpected message: " + ex.getMessage());
        }

        @Test
        @DisplayName("By 없는 prefix 사용은 일부 케이스에서 fail")
        void prefixWithoutBodyIsRejected() {
            // findBy + "" 는 매칭되지 않음 (PREFIXES 의 key는 모두 "By"로 끝나므로 By 뒤에 본문이 비면 IAE).
            // 명시적으로 unknown method 형태로 만들어 본문 비어 있음을 검증.
            interface Repo {
                Flux<Account> findBy();
            }
            Method m;
            try {
                m = Repo.class.getMethod("findBy");
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            assertThrows(IllegalArgumentException.class, () -> parser.tryParse(m));
        }
    }
}
