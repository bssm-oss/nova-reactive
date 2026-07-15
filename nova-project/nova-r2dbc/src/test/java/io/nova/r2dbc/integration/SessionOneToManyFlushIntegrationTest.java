package io.nova.r2dbc.integration;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import io.nova.core.SqlExecutionListener;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 세션(트랜잭션 바인딩) 안에서 {@code @OneToMany} child 컬렉션이 save 즉시 eager cascade가 아니라 flush의
 * diff-at-flush 파이프라인으로 지연 동기화되는지 H2로 검증한다(S1). {@link SessionCollectionFlushIntegrationTest}의
 * owning {@code @ManyToMany}/{@code @ElementCollection} 커버리지와 대칭이며, 여기서는 신규 child INSERT, orphan
 * DELETE(orphanRemoval=true)/FK-null UPDATE(orphanRemoval=false), 잔존 child의 스칼라 변경 자동 flush(JPA 완전
 * 파리티)를 검증한다. 세션 밖 stateless eager 경로는 {@code OneToManyCascadeIntegrationTest}가 보호하며 이 테스트
 * 스위트로 인한 회귀가 없어야 한다.
 */
class SessionOneToManyFlushIntegrationTest {
    private CapturingListener listener;
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        listener = new CapturingListener();
        support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Owner.class, Item.class, LooseOwner.class, LooseItem.class,
                OrderedOwner.class, OrderedItem.class).block();
    }

    private Owner seedOwnerWithTwoItems() {
        Owner owner = new Owner("o");
        owner.getItems().add(new Item("a"));
        owner.getItems().add(new Item("b"));
        return support.operations().save(owner).block();
    }

    @Test
    void unchangedCollectionInSessionEmitsNoChildSql() {
        Owner seeded = seedOwnerWithTwoItems();

        listener.clear();
        // 세션 안에서 로드만 하고 컬렉션을 바꾸지 않으면 flush가 item 테이블을 전혀 건드리지 않아야 한다.
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Owner.class, seeded.getId())))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(0, listener.count("item", "insert"), "무변경 flush는 INSERT를 내지 않아야 한다: " + listener.statements());
        assertEquals(0, listener.count("item", "delete"), "무변경 flush는 DELETE를 내지 않아야 한다: " + listener.statements());
        assertEquals(0, listener.count("item", "update"), "무변경 flush는 UPDATE를 내지 않아야 한다: " + listener.statements());
    }

    @Test
    void addingOneChildInSessionEmitsSingleInsertNoDelete() {
        Owner seeded = seedOwnerWithTwoItems();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Owner.class, seeded.getId())
                                .doOnNext(owner -> owner.getItems().add(new Item("c")))
                                .then()))
                .verifyComplete();

        assertEquals(1, listener.count("item", "insert"), "추가된 child 1건만 INSERT 되어야 한다: " + listener.statements());
        assertEquals(0, listener.count("item", "delete"), "추가만 했으므로 DELETE는 없어야 한다: " + listener.statements());

        StepVerifier.create(support.operations().findById(Owner.class, seeded.getId()))
                .assertNext(owner -> assertEquals(Set.of("a", "b", "c"),
                        owner.getItems().stream().map(Item::getLabel).collect(Collectors.toSet())))
                .verifyComplete();
    }

    @Test
    void removingOneChildWithOrphanRemovalEmitsSingleDeleteNoInsert() {
        Owner seeded = seedOwnerWithTwoItems();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Owner.class, seeded.getId())
                                .doOnNext(owner -> owner.getItems().removeIf(item -> "a".equals(item.getLabel())))
                                .then()))
                .verifyComplete();

        assertEquals(1, listener.count("item", "delete"), "제거된 child 1건만 DELETE 되어야 한다: " + listener.statements());
        assertEquals(0, listener.count("item", "insert"), "제거만 했으므로 INSERT는 없어야 한다: " + listener.statements());

        StepVerifier.create(support.operations().findById(Owner.class, seeded.getId()))
                .assertNext(owner -> assertEquals(Set.of("b"),
                        owner.getItems().stream().map(Item::getLabel).collect(Collectors.toSet())))
                .verifyComplete();
    }

    @Test
    void removingOneChildWithoutOrphanRemovalEmitsForeignKeyNullUpdate() {
        LooseOwner owner = new LooseOwner("lo");
        owner.getItems().add(new LooseItem("x"));
        owner.getItems().add(new LooseItem("y"));
        LooseOwner seeded = support.operations().save(owner).block();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(LooseOwner.class, seeded.getId())
                                .doOnNext(loaded -> loaded.getItems().removeIf(item -> "x".equals(item.getLabel())))
                                .then()))
                .verifyComplete();

        assertEquals(1, listener.count("loose_item", "update"),
                "orphanRemoval=false에서 제거된 child는 DELETE 대신 FK-null UPDATE 1건이어야 한다: " + listener.statements());
        assertEquals(0, listener.count("loose_item", "delete"),
                "orphanRemoval=false는 child row를 삭제하지 않아야 한다: " + listener.statements());

        // FK가 null이 됐을 뿐 row 자체는 남아 있어야 한다.
        StepVerifier.create(support.operations().count(LooseItem.class, io.nova.query.QuerySpec.empty()))
                .assertNext(count -> assertEquals(2L, count))
                .verifyComplete();
    }

    @Test
    void swappingChildInSessionEmitsOneInsertOneDelete() {
        Owner seeded = seedOwnerWithTwoItems();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Owner.class, seeded.getId())
                                .doOnNext(owner -> {
                                    owner.getItems().removeIf(item -> "a".equals(item.getLabel()));
                                    owner.getItems().add(new Item("c"));
                                })
                                .then()))
                .verifyComplete();

        assertEquals(1, listener.count("item", "insert"), "추가된 child 1건만 INSERT 되어야 한다: " + listener.statements());
        assertEquals(1, listener.count("item", "delete"), "제거된 child 1건만 DELETE 되어야 한다: " + listener.statements());

        StepVerifier.create(support.operations().findById(Owner.class, seeded.getId()))
                .assertNext(owner -> assertEquals(Set.of("b", "c"),
                        owner.getItems().stream().map(Item::getLabel).collect(Collectors.toSet())))
                .verifyComplete();
    }

    @Test
    void retainedChildScalarChangeIsAutoFlushed() {
        // JPA 완전 파리티: findById가 hydrate한 @OneToMany child는 세션 identity map에 편입되어, save() 호출
        // 없이 필드만 바꿔도 flush가 dirty diff로 UPDATE를 발행해야 한다.
        Owner seeded = seedOwnerWithTwoItems();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Owner.class, seeded.getId())
                                .doOnNext(owner -> owner.getItems().stream()
                                        .filter(item -> "a".equals(item.getLabel()))
                                        .findFirst()
                                        .orElseThrow()
                                        .setLabel("a-renamed"))
                                .then()))
                .verifyComplete();

        assertEquals(1, listener.count("item", "update"),
                "잔존 child의 스칼라 변경은 자기 ManagedEntry의 dirty diff로 UPDATE 1건을 내야 한다: " + listener.statements());
        assertEquals(0, listener.count("item", "insert"), "스칼라 변경만 했으므로 INSERT는 없어야 한다: " + listener.statements());
        assertEquals(0, listener.count("item", "delete"), "스칼라 변경만 했으므로 DELETE는 없어야 한다: " + listener.statements());

        StepVerifier.create(support.operations().findById(Owner.class, seeded.getId()))
                .assertNext(owner -> assertEquals(Set.of("a-renamed", "b"),
                        owner.getItems().stream().map(Item::getLabel).collect(Collectors.toSet())))
                .verifyComplete();
    }

    @Test
    @Disabled("S2: @OrderColumn 재인덱싱은 이 트랙에서 다루지 않는다")
    void reorderOnlyReindexesOrderColumn() {
        OrderedOwner owner = new OrderedOwner();
        owner.add(new OrderedItem("gamma"));
        owner.add(new OrderedItem("alpha"));
        Long id = support.operations().save(owner).map(OrderedOwner::getId).block();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(OrderedOwner.class, id)
                                .doOnNext(loaded -> {
                                    List<OrderedItem> reversed = new ArrayList<>(loaded.getItems());
                                    java.util.Collections.reverse(reversed);
                                    loaded.getItems().clear();
                                    loaded.getItems().addAll(reversed);
                                })
                                .then()))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(OrderedOwner.class, id))
                .assertNext(reloaded -> assertEquals(List.of("alpha", "gamma"), titles(reloaded)))
                .verifyComplete();
    }

    @Test
    void orderedOneToManyAllowsParentScalarChangeWhenMembershipUnchanged() {
        // LOW-1 회귀 가드: @OrderColumn 게이트는 Stage 1(멤버십 무변경) 이후로 옮겨졌고, 실제 add/remove가 있을
        // 때만 발동해야 한다. 컬렉션은 그대로 두고 parent 스칼라만 바꾸는 흔한 경로가 예외 없이 flush돼야 한다.
        OrderedOwner owner = new OrderedOwner();
        owner.setLabel("v1");
        owner.add(new OrderedItem("gamma"));
        owner.add(new OrderedItem("alpha"));
        Long id = support.operations().save(owner).map(OrderedOwner::getId).block();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(OrderedOwner.class, id)
                                .doOnNext(loaded -> loaded.setLabel("v2"))
                                .then()))
                .verifyComplete();

        assertEquals(1, listener.count("ordered_owner", "update"),
                "ordered @OneToMany parent의 스칼라 변경만으로는 예외 없이 UPDATE 1건이어야 한다: " + listener.statements());
        assertEquals(0, listener.count("ordered_item", "insert"), "컬렉션은 무변경이므로 INSERT 없음: " + listener.statements());
        assertEquals(0, listener.count("ordered_item", "delete"), "컬렉션은 무변경이므로 DELETE 없음: " + listener.statements());

        StepVerifier.create(support.operations().findById(OrderedOwner.class, id))
                .assertNext(reloaded -> {
                    assertEquals("v2", reloaded.getLabel());
                    assertEquals(List.of("gamma", "alpha"), titles(reloaded), "순서도 그대로 유지돼야 한다");
                })
                .verifyComplete();
    }

    @Test
    void orderedOneToManyStillFailsFastOnActualMembershipChange() {
        // LOW-1이 게이트를 느슨하게 하되 실제 멤버십 변경(add/remove)에서는 여전히 S2 미지원으로 거부해야 한다.
        OrderedOwner owner = new OrderedOwner();
        owner.add(new OrderedItem("gamma"));
        owner.add(new OrderedItem("alpha"));
        Long id = support.operations().save(owner).map(OrderedOwner::getId).block();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(OrderedOwner.class, id)
                                .doOnNext(loaded -> loaded.getItems().add(new OrderedItem("beta")))
                                .then()))
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("@OrderColumn")
                        && error.getMessage().contains("membership"))
                .verify();
    }

    @Test
    void commitThenFreshFindByIdMatchesExpectedChildSet() {
        Owner seeded = seedOwnerWithTwoItems();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Owner.class, seeded.getId())
                                .doOnNext(owner -> {
                                    owner.getItems().removeIf(item -> "a".equals(item.getLabel()));
                                    owner.getItems().add(new Item("c"));
                                    owner.getItems().add(new Item("d"));
                                })
                                .then()))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Owner.class, seeded.getId()))
                .assertNext(owner -> {
                    assertNotNull(owner);
                    assertEquals(Set.of("b", "c", "d"),
                            owner.getItems().stream().map(Item::getLabel).collect(Collectors.toSet()));
                })
                .verifyComplete();
    }

    private static List<String> titles(OrderedOwner owner) {
        return owner.getItems().stream().map(OrderedItem::getTitle).toList();
    }

    private static final class CapturingListener implements SqlExecutionListener {
        private final java.util.List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql());
        }

        void clear() {
            statements.clear();
        }

        java.util.List<String> statements() {
            return statements;
        }

        /** 주어진 테이블 이름을 언급하며 {@code op}(insert/delete/update)로 시작하는 SQL 문 개수. */
        long count(String table, String op) {
            return statements.stream()
                    .map(sql -> sql.toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.contains(table) && sql.startsWith(op))
                    .count();
        }
    }

    @Entity
    @Table(name = "owner")
    public static class Owner {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @OneToMany(targetEntity = Item.class, mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<Item> items = new ArrayList<>();

        public Owner() {
        }

        public Owner(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public List<Item> getItems() {
            return items;
        }
    }

    @Entity
    @Table(name = "item")
    public static class Item {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String label;

        @ManyToOne(targetEntity = Owner.class)
        @JoinColumn(name = "owner_id")
        private Owner owner;

        public Item() {
        }

        public Item(String label) {
            this.label = label;
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public Owner getOwner() {
            return owner;
        }
    }

    @Entity
    @Table(name = "loose_owner")
    public static class LooseOwner {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        // orphanRemoval 없이 cascade=PERSIST만 지정 — 컬렉션에서 빠진 child는 DELETE가 아니라 FK-null이어야 한다.
        @OneToMany(targetEntity = LooseItem.class, mappedBy = "owner", cascade = CascadeType.PERSIST)
        private List<LooseItem> items = new ArrayList<>();

        public LooseOwner() {
        }

        public LooseOwner(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public List<LooseItem> getItems() {
            return items;
        }
    }

    @Entity
    @Table(name = "loose_item")
    public static class LooseItem {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String label;

        @ManyToOne(targetEntity = LooseOwner.class)
        @JoinColumn(name = "owner_id")
        private LooseOwner owner;

        public LooseItem() {
        }

        public LooseItem(String label) {
            this.label = label;
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public LooseOwner getOwner() {
            return owner;
        }
    }

    @Entity
    @Table(name = "ordered_owner")
    public static class OrderedOwner {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String label;

        @OneToMany(targetEntity = OrderedItem.class, mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true)
        @OrderColumn(name = "items_order")
        private List<OrderedItem> items = new ArrayList<>();

        public OrderedOwner() {
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public List<OrderedItem> getItems() {
            return items;
        }

        public void add(OrderedItem item) {
            items.add(item);
        }
    }

    @Entity
    @Table(name = "ordered_item")
    public static class OrderedItem {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String title;

        @ManyToOne(targetEntity = OrderedOwner.class)
        @JoinColumn(name = "owner_id")
        private OrderedOwner owner;

        public OrderedItem() {
        }

        public OrderedItem(String title) {
            this.title = title;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public OrderedOwner getOwner() {
            return owner;
        }
    }
}
