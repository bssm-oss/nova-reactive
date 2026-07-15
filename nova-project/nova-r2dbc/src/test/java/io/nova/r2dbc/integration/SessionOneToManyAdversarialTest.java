package io.nova.r2dbc.integration;

import io.nova.core.SqlExecutionListener;
import io.nova.query.QuerySpec;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import jakarta.persistence.CascadeType;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1(@OneToMany 세션 diff-at-flush)에 대한 <b>독립 adversarial</b> 통합 테스트다. 저자 테스트
 * {@code SessionOneToManyFlushIntegrationTest}가 커버하지 않거나 약하게 다루는 엣지를 공격한다:
 * <ol>
 *   <li>한 flush에 다중 신규(null-id) child → HashSet 붕괴 없이 정확한 INSERT 수</li>
 *   <li>잔존 child 스칼라 변경 + commit 전 다른 findById(auto-flush) → IN_FLUSH 가드로 UPDATE 정확히 1회</li>
 *   <li>null 컬렉션(무터치) vs 빈 컬렉션(전 child 제거) 시맨틱 구분</li>
 *   <li>orphanRemoval=false FK-null 후 재add → 유령/이중 FK 없음</li>
 *   <li>중첩 소유(child가 @OneToMany + @ElementCollection 소유) 신규 그래프 저장 재귀</li>
 *   <li>reparenting(A→B 컬렉션 이동) → 조용한 손상 실증</li>
 *   <li>세션 밖 stateless cascade/orphan 회귀 없음</li>
 * </ol>
 * H2 통합·SQL 카운트 리스너 패턴은 {@link H2IntegrationTestSupport} +
 * {@code createWithManagedTransactions(listener)}를 그대로 따른다.
 */
class SessionOneToManyAdversarialTest {
    private CapturingListener listener;
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        listener = new CapturingListener();
        support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Bag.class, Marble.class, Shelf.class, Book.class,
                Org.class, Team.class, TeamMember.class).block();
    }

    private Bag seedBag(String name, String... colors) {
        Bag bag = new Bag(name);
        for (String color : colors) {
            bag.getItems().add(new Marble(color));
        }
        return support.operations().save(bag).block();
    }

    private Shelf seedShelf(String name, String... titles) {
        Shelf shelf = new Shelf(name);
        for (String title : titles) {
            shelf.getBooks().add(new Book(title));
        }
        return support.operations().save(shelf).block();
    }

    // ---------------------------------------------------------------------
    // Edge 0 (부수 발견): 로드한 빈 @OneToMany 컬렉션이 mutable해야 add가 가능
    // ---------------------------------------------------------------------
    @Test
    void loadedChildlessCollectionMustBeMutableForAdd() {
        // child가 0개인 parent를 세션에서 로드한 뒤 새 child를 add하는 것은 S1의 핵심 use-case다.
        // hydration이 immutable List.of()를 주입하면 add()가 UnsupportedOperationException으로 죽는다.
        Bag seeded = seedBag("empty");

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Bag.class, seeded.getId())
                                .doOnNext(bag -> bag.getItems().add(new Marble("first")))
                                .then()))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Bag.class, seeded.getId()))
                .assertNext(bag -> assertEquals(Set.of("first"),
                        bag.getItems().stream().map(Marble::getColor).collect(Collectors.toSet())))
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // Edge 1: 한 flush에 다중 신규 child (null-id HashSet 붕괴 탐지)
    // ---------------------------------------------------------------------
    @Test
    void threeBrandNewChildrenInOneFlushEmitExactlyThreeInserts() {
        // 기존 child 1개로 seed해 컬렉션을 mutable로 확보(빈-컬렉션 immutable 결함 회피)하고, 신규 3개를 add.
        Bag seeded = seedBag("b", "seed0");

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Bag.class, seeded.getId())
                                .doOnNext(bag -> {
                                    bag.getItems().add(new Marble("red"));
                                    bag.getItems().add(new Marble("green"));
                                    bag.getItems().add(new Marble("blue"));
                                })
                                .then()))
                .verifyComplete();

        assertEquals(3, listener.count("marble", "insert"),
                "null-id child 3개는 정확히 3건 INSERT 되어야 한다(HashSet 1건 붕괴 버그 탐지): " + listener.statements());

        StepVerifier.create(support.operations().findById(Bag.class, seeded.getId()))
                .assertNext(bag -> assertEquals(Set.of("seed0", "red", "green", "blue"),
                        bag.getItems().stream().map(Marble::getColor).collect(Collectors.toSet())))
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // Edge 2: 잔존 child 변경 + commit 전 다른 findById(auto-flush) → IN_FLUSH 가드
    // ---------------------------------------------------------------------
    @Test
    void retainedChildUpdateAutoFlushesExactlyOnceBeforeIntermediateSelect() {
        Bag a = seedBag("a", "x", "y");
        Bag b = seedBag("b", "z");

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Bag.class, a.getId())
                                .doOnNext(bag -> bag.getItems().stream()
                                        .filter(m -> "x".equals(m.getColor()))
                                        .findFirst().orElseThrow()
                                        .setColor("x2"))
                                // commit 전에 다른 parent를 findById → auto-flush가 먼저 돈다.
                                .then(ops.findById(Bag.class, b.getId()))
                                .then()))
                .verifyComplete();

        assertEquals(1, listener.count("marble", "update"),
                "잔존 child UPDATE는 정확히 1회여야 한다(중첩 flush 중복/누락 없음): " + listener.statements());
        assertEquals(0, listener.count("marble", "insert"), "스칼라 변경만 했으므로 INSERT 없음: " + listener.statements());
        assertEquals(0, listener.count("marble", "delete"), "스칼라 변경만 했으므로 DELETE 없음: " + listener.statements());

        // auto-flush 순서 검증: marble UPDATE가 두 번째 findById의 SELECT 전에 발행돼야 한다.
        int updateIdx = listener.firstIndex("update", "marble");
        int selectAfter = listener.firstSelectContainingAfter("bag", updateIdx);
        assertTrue(updateIdx >= 0, "marble UPDATE가 존재해야 한다: " + listener.statements());
        assertTrue(selectAfter > updateIdx,
                "auto-flush UPDATE가 중간 findById SELECT 전에 나와야 한다: " + listener.statements());

        StepVerifier.create(support.operations().findById(Bag.class, a.getId()))
                .assertNext(bag -> assertEquals(Set.of("x2", "y"),
                        bag.getItems().stream().map(Marble::getColor).collect(Collectors.toSet())))
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // Edge 3a: null 컬렉션 = 무터치
    // ---------------------------------------------------------------------
    @Test
    void nullCollectionInSessionLeavesChildrenUntouched() {
        Bag seeded = seedBag("b", "p", "q");

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Bag.class, seeded.getId())
                                .doOnNext(bag -> bag.setItems(null))
                                .then()))
                .verifyComplete();

        assertEquals(0, listener.count("marble", "delete"), "null 컬렉션은 DELETE를 내지 않아야 한다: " + listener.statements());
        assertEquals(0, listener.count("marble", "insert"), "null 컬렉션은 INSERT를 내지 않아야 한다: " + listener.statements());

        StepVerifier.create(support.operations().count(Marble.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(2L, count, "null 컬렉션이면 두 child row가 모두 남아야 한다"))
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // Edge 3b: 빈 컬렉션 = 전 child 제거(orphanRemoval=true → DELETE)
    // ---------------------------------------------------------------------
    @Test
    void emptyCollectionInSessionRemovesAllChildrenWithOrphanRemoval() {
        Bag seeded = seedBag("b", "p", "q");

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Bag.class, seeded.getId())
                                .doOnNext(bag -> bag.getItems().clear())
                                .then()))
                .verifyComplete();

        assertTrue(listener.count("marble", "delete") >= 1,
                "빈 컬렉션은 잔존 child를 DELETE 해야 한다: " + listener.statements());
        assertEquals(0, listener.count("marble", "insert"), "제거만 했으므로 INSERT 없음: " + listener.statements());

        StepVerifier.create(support.operations().count(Marble.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(0L, count, "빈 컬렉션이면 child row가 모두 삭제돼야 한다"))
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // Edge 4: orphanRemoval=false, FK-null 후 새 child 재add
    // ---------------------------------------------------------------------
    @Test
    void orphanRemovalFalseFkNullThenAddNewKeepsConsistentState() {
        Shelf seeded = seedShelf("s", "b1", "b2");

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Shelf.class, seeded.getId())
                                .doOnNext(shelf -> {
                                    shelf.getBooks().removeIf(book -> "b1".equals(book.getTitle()));
                                    shelf.getBooks().add(new Book("b3"));
                                })
                                .then()))
                .verifyComplete();

        // b1 = FK-null UPDATE, b3 = INSERT, b1 row는 삭제되지 않음.
        assertEquals(0, listener.count("book", "delete"),
                "orphanRemoval=false는 제거된 child를 DELETE 하지 않아야 한다: " + listener.statements());
        assertEquals(1, listener.count("book", "insert"), "새 child 1건만 INSERT: " + listener.statements());

        // 총 3행: b1(FK null), b2(shelf), b3(shelf). shelf 컬렉션은 정확히 {b2,b3}.
        StepVerifier.create(support.operations().count(Book.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(3L, count, "b1(고아)+b2+b3 = 3행이어야 한다"))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Shelf.class, seeded.getId()))
                .assertNext(shelf -> assertEquals(Set.of("b2", "b3"),
                        shelf.getBooks().stream().map(Book::getTitle).collect(Collectors.toSet()),
                        "shelf 컬렉션은 b1을 다시 포함하지 않고 b2,b3만이어야 한다(유령 FK 없음)"))
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // Edge 5: 중첩 소유 — 신규 parent + child(@OneToMany + @ElementCollection) 그래프
    // ---------------------------------------------------------------------
    @Test
    void newParentWithNestedChildGraphPersistsAllLevels() {
        Long orgId = support.operations().inTransaction(ops -> {
            Org org = new Org("acme");
            Team team = new Team("core");
            team.getMembers().add(new TeamMember("alice"));
            team.getMembers().add(new TeamMember("bob"));
            team.getSkills().add("java");
            team.getSkills().add("sql");
            org.getTeams().add(team);
            return ops.save(org).map(Org::getId);
        }).block();

        assertNotNull(orgId, "새 org가 저장돼 id가 있어야 한다");

        // 참고: findById(Org)는 team만 hydrate하고 손자(members/skills)는 재귀 hydrate하지 않으므로
        // 중첩 컬렉션의 영속 여부는 DB row를 직접 세거나 findById(Team)로 한 단계 로드해 확인한다.
        Long teamId = support.operations().findById(Org.class, orgId)
                .map(org -> {
                    assertEquals(1, org.getTeams().size(), "team 1개가 반영돼야 한다: " + org.getTeams());
                    return org.getTeams().get(0).getId();
                })
                .block();

        StepVerifier.create(support.operations().count(TeamMember.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(2L, count,
                        "중첩 @OneToMany member 2건이 persistChildInFlush 재귀로 저장돼야 한다"))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Team.class, teamId))
                .assertNext(team -> {
                    assertEquals(Set.of("alice", "bob"),
                            team.getMembers().stream().map(TeamMember::getName).collect(Collectors.toSet()),
                            "중첩 @OneToMany member가 저장돼 team으로 로드돼야 한다");
                    assertEquals(Set.of("java", "sql"), team.getSkills(),
                            "중첩 @ElementCollection skill이 신규 child persist 시 저장돼야 한다");
                })
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // Edge 6a: reparenting, orphanRemoval=false (컬렉션 이동만) → 스코프 확정: fail-fast로 거부
    // ---------------------------------------------------------------------
    @Test
    void reparentingWithoutOrphanRemovalMovesChildToNewParent() {
        // D2/M2 스코프 확정: 컬렉션 멤버십만으로(child 자신의 owning @ManyToOne 필드는 건드리지 않고) 이미
        // 관리 중인 child를 A→B로 옮기면 A의 diffOneToMany가 FK-null(orphanRemoval=false)/DELETE(true)로
        // 조용히 처리하고 B는 그 사실을 모른 채 아무 것도 하지 않아 데이터가 조용히 사라진다. 지금은 실지원
        // 대신 명확한 예외로 거부한다(실지원은 후속 트랙).
        Shelf a = seedShelf("a", "novel");
        Shelf b = seedShelf("b", "filler"); // filler로 B 컬렉션을 mutable 확보(빈-컬렉션 결함 회피).
        Long bookId = support.operations().findById(Shelf.class, a.getId())
                .map(shelf -> shelf.getBooks().get(0).getId()).block();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Shelf.class, a.getId())
                                .flatMap(shelfA -> ops.findById(Shelf.class, b.getId())
                                        .doOnNext(shelfB -> {
                                            Book moved = shelfA.getBooks().get(0);
                                            shelfA.getBooks().remove(moved);
                                            shelfB.getBooks().add(moved);
                                        }))
                                .then()))
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("reparenting")
                        && error.getMessage().contains("shelf"))
                .verify();

        // 트랜잭션이 롤백돼 원래 상태(book은 여전히 shelf A 소속)가 보존돼야 한다 — 조용한 손실이 없다는 증명.
        StepVerifier.create(support.operations().findById(Book.class, bookId))
                .assertNext(book -> {
                    assertNotNull(book.getShelf(),
                            "실패한 트랜잭션이 롤백되지 않아 FK가 null로 남으면 안 된다");
                    assertEquals(a.getId(), book.getShelf().getId(),
                            "롤백 후 book은 여전히 원래 shelf A 소속이어야 한다");
                })
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Shelf.class, b.getId()))
                .assertNext(shelf -> assertEquals(Set.of("filler"),
                        shelf.getBooks().stream().map(Book::getTitle).collect(Collectors.toSet()),
                        "롤백 후 shelf B는 원래 filler만 담고 있어야 한다"))
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // Edge 6b: reparenting, orphanRemoval=true (컬렉션 이동만) → 스코프 확정: fail-fast로 거부
    // ---------------------------------------------------------------------
    @Test
    void reparentingWithOrphanRemovalMovesChildNotDeletesIt() {
        // D2/M2 스코프 확정(위 Edge 6a와 대칭): orphanRemoval=true에서는 A의 diffOneToMany가 이동 child를
        // 실제 DELETE 해버릴 수 있어 더 위험하다 — 마찬가지로 fail-fast로 거부한다.
        Bag a = seedBag("a", "onlymarble");
        Bag b = seedBag("b", "filler"); // filler로 B 컬렉션을 mutable 확보(빈-컬렉션 결함 회피).
        Long marbleId = support.operations().findById(Bag.class, a.getId())
                .map(bag -> bag.getItems().get(0).getId()).block();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Bag.class, a.getId())
                                .flatMap(bagA -> ops.findById(Bag.class, b.getId())
                                        .doOnNext(bagB -> {
                                            Marble moved = bagA.getItems().get(0);
                                            bagA.getItems().remove(moved);
                                            bagB.getItems().add(moved);
                                        }))
                                .then()))
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("reparenting")
                        && error.getMessage().contains("bag"))
                .verify();

        // 트랜잭션이 롤백돼 marble이 삭제되지 않고 원래 bag A 소속으로 남아야 한다.
        StepVerifier.create(support.operations().count(Marble.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(2L, count,
                        "롤백 후 onlymarble(A)+filler(B) = 2행이 그대로 남아야 한다"))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Marble.class, marbleId))
                .assertNext(marble -> {
                    assertNotNull(marble.getBag(), "롤백 후에도 marble FK가 null로 붕괴하면 안 된다");
                    assertEquals(a.getId(), marble.getBag().getId(), "롤백 후 marble은 여전히 원래 bag A 소속이어야 한다");
                })
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // Edge 6c/6d (open question): D2 예외 메시지가 안내하는 "정식" reparent 경로 — child의 owning @ManyToOne을
    // 직접 set한 뒤 컬렉션을 옮기면(양쪽 다 갱신) fail-fast에 걸리지 않고 실제로 안전하게 이동하는지 검증한다.
    // ---------------------------------------------------------------------
    @Test
    void properReparentByUpdatingOwningSideMovesChildToNewParent_noOrphanRemoval() {
        // Shelf: cascade=PERSIST만, orphanRemoval=false.
        Shelf a = seedShelf("a", "novel");
        Shelf b = seedShelf("b", "filler");
        Long bookId = support.operations().findById(Shelf.class, a.getId())
                .map(shelf -> shelf.getBooks().get(0).getId()).block();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Shelf.class, a.getId())
                                .flatMap(shelfA -> ops.findById(Shelf.class, b.getId())
                                        .doOnNext(shelfB -> {
                                            Book moved = shelfA.getBooks().get(0);
                                            // 정식 경로: 소유측(@ManyToOne)을 직접 set한 뒤 양쪽 컬렉션을 옮긴다.
                                            moved.setShelf(shelfB);
                                            shelfA.getBooks().remove(moved);
                                            shelfB.getBooks().add(moved);
                                        }))
                                .then()))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Book.class, bookId))
                .assertNext(book -> {
                    assertNotNull(book.getShelf(), "정식 reparent 후 FK가 null이면 안 된다");
                    assertEquals(b.getId(), book.getShelf().getId(), "정식 reparent 후 book은 shelf B를 가리켜야 한다");
                })
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Shelf.class, b.getId()))
                .assertNext(shelf -> assertEquals(Set.of("filler", "novel"),
                        shelf.getBooks().stream().map(Book::getTitle).collect(Collectors.toSet()),
                        "shelf B가 filler와 이동한 novel을 담아야 한다"))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Shelf.class, a.getId()))
                .assertNext(shelf -> assertEquals(Set.of(),
                        shelf.getBooks().stream().map(Book::getTitle).collect(Collectors.toSet()),
                        "shelf A는 더 이상 novel을 담지 않아야 한다"))
                .verifyComplete();
    }

    @Test
    @Disabled("KNOWN DEFECT (open question, reported not fixed): flush iteration order races the losing side's"
            + " removeOrphans DELETE against the moved child's own scalar-dirty-check FK UPDATE. Session"
            + " registration order for this scenario is [bagA, movedMarble, bagB, fillerMarble] (parent registered"
            + " on load, its then-current children registered right after via captureCollectionSnapshots ->"
            + " registerLoadedOneToManyChildren, before the next findById begins). flush() iterates managedEntries()"
            + " in that exact order, so flushEntry(bagA) — which runs diffOneToMany and, because orphanRemoval=true"
            + " and the moved marble is no longer in bagA's current collection, calls removeOrphans (SimpleReactiveEntityOperations"
            + " ~diffOneToMany orphan-handling, method around :2200) — executes and DELETEs the marble row *before*"
            + " flushEntry(movedMarble) ever runs. removeOrphans is an unconditional DELETE ... WHERE bag_id = A AND"
            + " id NOT IN (retained); it has no way to know the row is about to be re-homed by a later flushEntry in"
            + " the same flush. By the time movedMarble's own flushEntry executes its dirty-check UPDATE (SET"
            + " bag_id = B), the row no longer exists, so the UPDATE silently affects 0 rows. Net effect: even"
            + " though the user followed the exact 'set the owning @ManyToOne side' procedure the D2 fail-fast"
            + " message recommends, the marble is permanently lost when orphanRemoval=true. The FK-null variant"
            + " (properReparentByUpdatingOwningSideMovesChildToNewParent_noOrphanRemoval, orphanRemoval=false) does"
            + " NOT lose data because disownOrphans issues an UPDATE ... SET fk = NULL instead of a DELETE, and"
            + " movedBook's later flushEntry UPDATE simply overwrites that NULL back to the correct owner — a"
            + " row survives an UPDATE-then-UPDATE race but not a DELETE-then-UPDATE race. Repro: input = seed bagA"
            + " with 1 marble + bagB with 1 marble, in one session load both, call moved.setBag(bagB) (owning-side"
            + " update) then move it between the in-memory collections, commit; expected = 2 marble rows survive"
            + " (filler + moved, moved pointing at bagB); actual = 1 row (moved was deleted). Left disabled per"
            + " coordinator instruction (report, do not fix in this round).")
    void properReparentByUpdatingOwningSideMovesChildToNewParent_withOrphanRemoval() {
        // Bag: cascade=ALL + orphanRemoval=true — losing side(A)의 removeOrphans가 DELETE를 낼 수 있는 더 위험한 조합.
        Bag a = seedBag("a", "onlymarble");
        Bag b = seedBag("b", "filler");
        Long marbleId = support.operations().findById(Bag.class, a.getId())
                .map(bag -> bag.getItems().get(0).getId()).block();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Bag.class, a.getId())
                                .flatMap(bagA -> ops.findById(Bag.class, b.getId())
                                        .doOnNext(bagB -> {
                                            Marble moved = bagA.getItems().get(0);
                                            // 정식 경로: 소유측(@ManyToOne)을 직접 set한 뒤 양쪽 컬렉션을 옮긴다.
                                            moved.setBag(bagB);
                                            bagA.getItems().remove(moved);
                                            bagB.getItems().add(moved);
                                        }))
                                .then()))
                .verifyComplete();

        StepVerifier.create(support.operations().count(Marble.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(2L, count,
                        "정식 reparent는 이동한 marble을 orphanRemoval로 지우면 안 된다(filler+moved=2행 유지)"))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Marble.class, marbleId))
                .assertNext(marble -> {
                    assertNotNull(marble.getBag(), "정식 reparent 후 FK가 null이면 안 된다");
                    assertEquals(b.getId(), marble.getBag().getId(), "정식 reparent 후 marble은 bag B를 가리켜야 한다");
                })
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Bag.class, b.getId()))
                .assertNext(bag -> assertEquals(Set.of("filler", "onlymarble"),
                        bag.getItems().stream().map(Marble::getColor).collect(Collectors.toSet()),
                        "bag B가 filler와 이동한 onlymarble을 담아야 한다"))
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // Edge 7: stateless(세션 밖) cascade/orphan 회귀 없음
    // ---------------------------------------------------------------------
    @Test
    void statelessOneToManyCascadeAndOrphanStillWork() {
        Bag seeded = seedBag("b", "m1", "m2");

        StepVerifier.create(support.operations().count(Marble.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(2L, count, "stateless cascade로 2 child가 저장돼야 한다"))
                .verifyComplete();

        // 세션 밖에서 로드 → 하나 제거 → 다시 save → eager orphan 삭제.
        Bag reloaded = support.operations().findById(Bag.class, seeded.getId()).block();
        reloaded.getItems().removeIf(m -> "m1".equals(m.getColor()));
        support.operations().save(reloaded).block();

        StepVerifier.create(support.operations().count(Marble.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(1L, count, "stateless eager orphanRemoval로 1 child만 남아야 한다"))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Bag.class, seeded.getId()))
                .assertNext(bag -> assertEquals(Set.of("m2"),
                        bag.getItems().stream().map(Marble::getColor).collect(Collectors.toSet())))
                .verifyComplete();
    }

    // ---------------------------------------------------------------------
    // 리스너
    // ---------------------------------------------------------------------
    private static final class CapturingListener implements SqlExecutionListener {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql());
        }

        void clear() {
            statements.clear();
        }

        List<String> statements() {
            return statements;
        }

        long count(String table, String op) {
            return statements.stream()
                    .map(sql -> sql.toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.contains(table) && sql.startsWith(op))
                    .count();
        }

        int firstIndex(String op, String table) {
            for (int i = 0; i < statements.size(); i++) {
                String sql = statements.get(i).toLowerCase(Locale.ROOT);
                if (sql.startsWith(op) && sql.contains(table)) {
                    return i;
                }
            }
            return -1;
        }

        int firstSelectContainingAfter(String table, int afterIndex) {
            for (int i = afterIndex + 1; i < statements.size(); i++) {
                String sql = statements.get(i).toLowerCase(Locale.ROOT);
                if (sql.startsWith("select") && sql.contains(table)) {
                    return i;
                }
            }
            return -1;
        }
    }

    // ---------------------------------------------------------------------
    // 픽스처 (in-file POJO)
    // ---------------------------------------------------------------------
    @Entity
    @Table(name = "bag")
    public static class Bag {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @OneToMany(targetEntity = Marble.class, mappedBy = "bag", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<Marble> items = new ArrayList<>();

        public Bag() {
        }

        public Bag(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public List<Marble> getItems() {
            return items;
        }

        public void setItems(List<Marble> items) {
            this.items = items;
        }
    }

    @Entity
    @Table(name = "marble")
    public static class Marble {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String color;

        @ManyToOne(targetEntity = Bag.class)
        @JoinColumn(name = "bag_id")
        private Bag bag;

        public Marble() {
        }

        public Marble(String color) {
            this.color = color;
        }

        public Long getId() {
            return id;
        }

        public String getColor() {
            return color;
        }

        public void setColor(String color) {
            this.color = color;
        }

        public Bag getBag() {
            return bag;
        }

        public void setBag(Bag bag) {
            this.bag = bag;
        }
    }

    @Entity
    @Table(name = "shelf")
    public static class Shelf {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        // orphanRemoval 없이 cascade=PERSIST만 — 제거된 child는 FK-null이어야 한다.
        @OneToMany(targetEntity = Book.class, mappedBy = "shelf", cascade = CascadeType.PERSIST)
        private List<Book> books = new ArrayList<>();

        public Shelf() {
        }

        public Shelf(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public List<Book> getBooks() {
            return books;
        }
    }

    @Entity
    @Table(name = "book")
    public static class Book {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String title;

        @ManyToOne(targetEntity = Shelf.class)
        @JoinColumn(name = "shelf_id")
        private Shelf shelf;

        public Book() {
        }

        public Book(String title) {
            this.title = title;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public Shelf getShelf() {
            return shelf;
        }

        public void setShelf(Shelf shelf) {
            this.shelf = shelf;
        }
    }

    @Entity
    @Table(name = "org")
    public static class Org {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @OneToMany(targetEntity = Team.class, mappedBy = "org", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<Team> teams = new ArrayList<>();

        public Org() {
        }

        public Org(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public List<Team> getTeams() {
            return teams;
        }
    }

    @Entity
    @Table(name = "team")
    public static class Team {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @ManyToOne(targetEntity = Org.class)
        @JoinColumn(name = "org_id")
        private Org org;

        @OneToMany(targetEntity = TeamMember.class, mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<TeamMember> members = new ArrayList<>();

        @ElementCollection
        private Set<String> skills = new LinkedHashSet<>();

        public Team() {
        }

        public Team(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Org getOrg() {
            return org;
        }

        public List<TeamMember> getMembers() {
            return members;
        }

        public Set<String> getSkills() {
            return skills;
        }
    }

    @Entity
    @Table(name = "team_member")
    public static class TeamMember {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @ManyToOne(targetEntity = Team.class)
        @JoinColumn(name = "team_id")
        private Team team;

        public TeamMember() {
        }

        public TeamMember(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Team getTeam() {
            return team;
        }
    }
}
