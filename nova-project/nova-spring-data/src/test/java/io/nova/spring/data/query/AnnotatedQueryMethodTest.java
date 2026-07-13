package io.nova.spring.data.query;

import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.Slice;
import io.nova.spring.data.Modifying;
import io.nova.spring.data.Param;
import io.nova.spring.data.Query;
import io.nova.spring.data.ReactiveCrudRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnnotatedQueryMethod} 파싱/바인딩 계획과 fail-fast 거부를 순수 리플렉션(무-DB)으로 검증한다.
 */
class AnnotatedQueryMethodTest {

    static final class Account {
        Long id;
        String email;
    }

    private static Method method(Class<?> iface, String name, Class<?>... params) {
        try {
            return iface.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AnnotatedQueryMethod parse(Class<?> iface, String name, Class<?>... params) {
        return AnnotatedQueryMethod.parse(method(iface, name, params), Account.class);
    }

    interface Repo extends ReactiveCrudRepository<Account, Long> {
        // @Query 없음 → parse는 null.
        Flux<Account> plainList();

        @Query("SELECT a FROM Account a WHERE a.email = :email")
        Mono<Account> byEmail(@Param("email") String email);

        @Query("SELECT a FROM Account a WHERE a.email = ?1")
        Flux<Account> byEmailPositional(String email);

        @Query("SELECT a.email FROM Account a")
        Flux<String> emails();

        @Query(value = "SELECT * FROM accounts WHERE email = :email", nativeQuery = true)
        Flux<Account> nativeByEmail(@Param("email") String email);

        @Modifying
        @Query("UPDATE Account a SET a.email = :email WHERE a.id = :id")
        Mono<Long> renameById(@Param("email") String email, @Param("id") Long id);

        @Modifying
        @Query(value = "DELETE FROM accounts WHERE id = ?1", nativeQuery = true)
        Mono<Integer> nativeDelete(Long id);

        @Query("SELECT a FROM Account a")
        Mono<Page<Account>> pageAll(Pageable pageable);

        @Query("SELECT a FROM Account a")
        Mono<Slice<Account>> sliceAll(Pageable pageable);
    }

    @Test
    @DisplayName("@Query 없는 메서드는 parse가 null을 반환한다")
    void noAnnotationParsesToNull() {
        assertNull(parse(Repo.class, "plainList"));
    }

    @Test
    @DisplayName("named JPQL @Query: 이름 바인딩 + positional 번호가 모두 채워진다")
    void namedJpqlQuery() {
        AnnotatedQueryMethod meta = parse(Repo.class, "byEmail", String.class);
        assertFalse(meta.nativeQuery());
        assertFalse(meta.modifying());
        assertEquals(AnnotatedQueryMethod.Shape.MONO_SINGLE, meta.shape());
        assertTrue(meta.isEntityElement(Account.class));
        assertEquals(1, meta.bindables().size());
        assertEquals("email", meta.bindables().get(0).name());
        assertEquals(1, meta.bindables().get(0).positional());
        assertEquals(0, meta.bindables().get(0).argIndex());
    }

    @Test
    @DisplayName("positional JPQL @Query: @Param 없으면 name은 null, positional만")
    void positionalJpqlQuery() {
        AnnotatedQueryMethod meta = parse(Repo.class, "byEmailPositional", String.class);
        assertEquals(AnnotatedQueryMethod.Shape.FLUX, meta.shape());
        assertNull(meta.bindables().get(0).name());
        assertEquals(1, meta.bindables().get(0).positional());
    }

    @Test
    @DisplayName("스칼라 SELECT는 elementType이 엔티티가 아니다")
    void scalarSelect() {
        AnnotatedQueryMethod meta = parse(Repo.class, "emails");
        assertEquals(AnnotatedQueryMethod.Shape.FLUX, meta.shape());
        assertEquals(String.class, meta.elementType());
        assertFalse(meta.isEntityElement(Account.class));
    }

    @Test
    @DisplayName("native @Query 플래그와 엔티티 반환")
    void nativeQueryFlag() {
        AnnotatedQueryMethod meta = parse(Repo.class, "nativeByEmail", String.class);
        assertTrue(meta.nativeQuery());
        assertEquals(AnnotatedQueryMethod.Shape.FLUX, meta.shape());
        assertTrue(meta.isEntityElement(Account.class));
    }

    @Test
    @DisplayName("@Modifying JPQL: MODIFYING 형태 + Mono<Long> 결과 타입")
    void modifyingJpql() {
        AnnotatedQueryMethod meta = parse(Repo.class, "renameById", String.class, Long.class);
        assertTrue(meta.modifying());
        assertEquals(AnnotatedQueryMethod.Shape.MODIFYING, meta.shape());
        assertEquals(Long.class, meta.modifyingResultType());
        assertEquals(2, meta.bindables().size());
    }

    @Test
    @DisplayName("@Modifying native: Mono<Integer> 결과 타입")
    void modifyingNative() {
        AnnotatedQueryMethod meta = parse(Repo.class, "nativeDelete", Long.class);
        assertTrue(meta.modifying());
        assertTrue(meta.nativeQuery());
        assertEquals(Integer.class, meta.modifyingResultType());
    }

    @Test
    @DisplayName("Mono<Page<T>>/Mono<Slice<T>>는 Nova paging 형태 + Pageable 파라미터 인덱스")
    void pagingShapes() {
        AnnotatedQueryMethod page = parse(Repo.class, "pageAll", Pageable.class);
        assertEquals(AnnotatedQueryMethod.Shape.NOVA_PAGE, page.shape());
        assertTrue(page.hasPageable());
        assertFalse(page.pageableSpring());
        assertEquals(0, page.pageableArgIndex());
        assertTrue(page.bindables().isEmpty());

        AnnotatedQueryMethod slice = parse(Repo.class, "sliceAll", Pageable.class);
        assertEquals(AnnotatedQueryMethod.Shape.NOVA_SLICE, slice.shape());
    }

    // ------------------------------------------------------------------------------------------
    // Spring @Param honored + Spring paging shape
    // ------------------------------------------------------------------------------------------

    interface SpringRepo extends ReactiveCrudRepository<Account, Long> {
        @Query("SELECT a FROM Account a WHERE a.email = :email")
        Mono<Account> byEmail(@org.springframework.data.repository.query.Param("email") String email);

        @Query("SELECT a FROM Account a")
        Mono<org.springframework.data.domain.Page<Account>> springPage(
                org.springframework.data.domain.Pageable pageable);
    }

    @Test
    @DisplayName("Spring @Param도 이름 기준으로 존중된다")
    void springParamHonored() {
        AnnotatedQueryMethod meta = parse(SpringRepo.class, "byEmail", String.class);
        assertEquals("email", meta.bindables().get(0).name());
    }

    @Test
    @DisplayName("Spring Pageable + Mono<Spring Page> → SPRING_PAGE 형태")
    void springPagingShape() {
        AnnotatedQueryMethod meta = parse(SpringRepo.class, "springPage",
                org.springframework.data.domain.Pageable.class);
        assertEquals(AnnotatedQueryMethod.Shape.SPRING_PAGE, meta.shape());
        assertTrue(meta.pageableSpring());
    }

    // ------------------------------------------------------------------------------------------
    // fail-fast rejections
    // ------------------------------------------------------------------------------------------

    interface BadRepo extends ReactiveCrudRepository<Account, Long> {
        @Query("   ")
        Flux<Account> blankValue();

        @Query("SELECT a FROM Account a")
        Flux<Account> withSort(io.nova.query.Sort sort);

        @Modifying
        @Query("SELECT a FROM Account a")
        Mono<Long> modifyingSelect();

        @Query("UPDATE Account a SET a.email = 'x' WHERE a.id = ?1")
        Mono<Long> updateWithoutModifying(Long id);

        @Query(value = "SELECT * FROM accounts", nativeQuery = true)
        Mono<Page<Account>> nativePaging(Pageable pageable);

        @Query("SELECT a FROM Account a")
        Mono<Account> monoWithPageable(Pageable pageable);

        @Query("SELECT a FROM Account a")
        Mono<Page<Account>> pageWithoutPageable();

        @Modifying
        @Query("UPDATE Account a SET a.email = 'x'")
        Mono<String> modifyingWrongReturn();

        @Query("SELECT a FROM Account a")
        Mono<Page<Account>> novaPageWithSpringPageable(org.springframework.data.domain.Pageable pageable);
    }

    @Test
    @DisplayName("blank @Query value는 거부")
    void rejectsBlankValue() {
        assertThrows(AnnotatedQueryException.class, () -> parse(BadRepo.class, "blankValue"));
    }

    @Test
    @DisplayName("Sort 파라미터는 거부(ORDER BY 사용)")
    void rejectsSortParam() {
        assertThrows(AnnotatedQueryException.class,
                () -> parse(BadRepo.class, "withSort", io.nova.query.Sort.class));
    }

    @Test
    @DisplayName("@Modifying + SELECT는 거부")
    void rejectsModifyingSelect() {
        assertThrows(AnnotatedQueryException.class, () -> parse(BadRepo.class, "modifyingSelect"));
    }

    @Test
    @DisplayName("@Modifying 없는 UPDATE JPQL은 거부")
    void rejectsUpdateWithoutModifying() {
        assertThrows(AnnotatedQueryException.class,
                () -> parse(BadRepo.class, "updateWithoutModifying", Long.class));
    }

    @Test
    @DisplayName("native + Pageable은 v1 미지원으로 거부")
    void rejectsNativePaging() {
        assertThrows(AnnotatedQueryException.class,
                () -> parse(BadRepo.class, "nativePaging", Pageable.class));
    }

    @Test
    @DisplayName("Mono<T> 단건 + Pageable은 거부")
    void rejectsMonoWithPageable() {
        assertThrows(AnnotatedQueryException.class,
                () -> parse(BadRepo.class, "monoWithPageable", Pageable.class));
    }

    @Test
    @DisplayName("Page 반환인데 Pageable 파라미터 없으면 거부")
    void rejectsPageWithoutPageable() {
        assertThrows(AnnotatedQueryException.class, () -> parse(BadRepo.class, "pageWithoutPageable"));
    }

    @Test
    @DisplayName("@Modifying 잘못된 반환 타입은 거부")
    void rejectsModifyingWrongReturn() {
        assertThrows(AnnotatedQueryException.class, () -> parse(BadRepo.class, "modifyingWrongReturn"));
    }

    @Test
    @DisplayName("Nova Page 반환 + Spring Pageable 파라미터 불일치는 거부")
    void rejectsNovaPageWithSpringPageable() {
        assertThrows(AnnotatedQueryException.class,
                () -> parse(BadRepo.class, "novaPageWithSpringPageable",
                        org.springframework.data.domain.Pageable.class));
    }
}
