package io.nova.r2dbc.integration;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyClass;
import jakarta.persistence.Table;
import io.nova.core.SqlExecutionListener;
import io.nova.query.QuerySpec;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @MapKeyClass}가 단일 {@code @Id} {@code @Entity} key 클래스를 가리키는 {@code @ElementCollection Map<K,V>}가
 * H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다 — {@link EmbeddableMapKeyElementCollectionIntegrationTest}의
 * entity-key 미러. key 컬럼은 참조 {@code @Id}와 동일한 단일 FK 저장 규칙을 쓰고, hydration은 2-hop 배치 로드로
 * key entity의 non-id 필드까지 완전히 채운다(N+1 없음, distinct id당 1인스턴스 공유), dangling FK는 id-only stub으로
 * 보존한다. {@link KeyEntity}는 의도적으로 {@code equals}/{@code hashCode}를 오버라이드하지 않는다 — map 재구성과
 * flush 시점 dirty-check가 엔티티 identity가 아니라 {@code @Id} 값으로 비교됨을 증명한다.
 */
class EntityMapKeyElementCollectionIntegrationTest {
    private CapturingListener listener;
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        listener = new CapturingListener();
        support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Owner.class, KeyEntity.class, UuidKeyEntity.class).block();
    }

    @Test
    void roundTripsBasicValueAndHydratesKeyEntityNonIdFields() {
        KeyEntity k1 = support.operations().save(new KeyEntity("alpha")).block();
        KeyEntity k2 = support.operations().save(new KeyEntity("beta")).block();

        Owner owner = new Owner();
        owner.getTags().put(k1, "one");
        owner.getTags().put(k2, "two");
        Long id = support.operations().save(owner).map(Owner::getId).block();

        StepVerifier.create(support.operations().findById(Owner.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getTags().size());
                    String valueForAlpha = null;
                    String valueForBeta = null;
                    for (Map.Entry<KeyEntity, String> entry : loaded.getTags().entrySet()) {
                        assertNotNull(entry.getKey().getId(), "key entity @Id는 채워져 있어야 한다");
                        // non-@Id 필드까지 채워져 있어야 한다 — 2nd hop 배치 로드가 실제 일어났다는 증거(id-only
                        // stub이 아니라 완전 엔티티).
                        assertNotNull(entry.getKey().getLabel(),
                                "key entity의 non-@Id 필드가 채워져 있어야 한다(배치 로드 증명)");
                        if (entry.getKey().getId().equals(k1.getId())) {
                            valueForAlpha = entry.getValue();
                            assertEquals("alpha", entry.getKey().getLabel());
                        } else if (entry.getKey().getId().equals(k2.getId())) {
                            valueForBeta = entry.getValue();
                            assertEquals("beta", entry.getKey().getLabel());
                        }
                    }
                    assertEquals("one", valueForAlpha);
                    assertEquals("two", valueForBeta);
                })
                .verifyComplete();
    }

    @Test
    void roundTripsEmbeddableValueWithEntityKey() {
        KeyEntity k1 = support.operations().save(new KeyEntity("alpha")).block();

        Owner owner = new Owner();
        owner.getPrices().put(k1, new Amount("USD", 1000));
        Long id = support.operations().save(owner).map(Owner::getId).block();

        StepVerifier.create(support.operations().findById(Owner.class, id))
                .assertNext(loaded -> {
                    assertEquals(1, loaded.getPrices().size());
                    Map.Entry<KeyEntity, Amount> entry = loaded.getPrices().entrySet().iterator().next();
                    assertEquals("alpha", entry.getKey().getLabel());
                    assertEquals(new Amount("USD", 1000), entry.getValue());
                })
                .verifyComplete();
    }

    @Test
    void sharedKeyAcrossOwnersLoadsInOneBatchQueryWithSharedInstance() {
        KeyEntity shared = support.operations().save(new KeyEntity("shared")).block();

        Owner ownerA = new Owner();
        ownerA.getTags().put(shared, "from-a");
        Owner ownerB = new Owner();
        ownerB.getTags().put(shared, "from-b");
        StepVerifier.create(
                        support.operations().save(ownerA)
                                .then(support.operations().save(ownerB)))
                .expectNextCount(1)
                .verifyComplete();

        listener.clear();
        StepVerifier.create(support.operations().findAll(Owner.class, QuerySpec.empty()).collectList())
                .assertNext(owners -> {
                    assertEquals(2, owners.size());
                    KeyEntity keyA = owners.get(0).getTags().keySet().iterator().next();
                    KeyEntity keyB = owners.get(1).getTags().keySet().iterator().next();
                    assertEquals(shared.getId(), keyA.getId());
                    assertEquals(shared.getId(), keyB.getId());
                    assertSame(keyA, keyB,
                            "distinct id당 1인스턴스 — 공유 키를 참조하는 두 owner는 같은 KeyEntity 인스턴스를 봐야 한다");
                })
                .verifyComplete();

        // 자식(owner)이 2개여도 key entity SELECT는 정확히 1회 — N+1이 없음을 증명한다.
        assertEquals(1, listener.selectCountFor("key_entity"),
                "공유 key entity는 owner 수와 무관하게 한 번의 배치 쿼리로 로드돼야 한다");
    }

    @Test
    void uuidIdEntityKeyRoundTrips() {
        UuidKeyEntity k1 = support.operations().save(new UuidKeyEntity("u-alpha")).block();

        Owner owner = new Owner();
        owner.getByUuidKey().put(k1, "value-1");
        Long id = support.operations().save(owner).map(Owner::getId).block();

        StepVerifier.create(support.operations().findById(Owner.class, id))
                .assertNext(loaded -> {
                    assertEquals(1, loaded.getByUuidKey().size());
                    Map.Entry<UuidKeyEntity, String> entry = loaded.getByUuidKey().entrySet().iterator().next();
                    assertEquals(k1.getId(), entry.getKey().getId());
                    assertEquals("u-alpha", entry.getKey().getLabel());
                    assertEquals("value-1", entry.getValue());
                })
                .verifyComplete();
    }

    @Test
    void danglingForeignKeyPreservesIdOnlyStub() {
        KeyEntity k1 = support.operations().save(new KeyEntity("alpha")).block();
        Owner owner = new Owner();
        owner.getTags().put(k1, "one");
        Long id = support.operations().save(owner).map(Owner::getId).block();

        // key entity 행을 직접 삭제해 collection table row는 남았지만 key entity 대상이 없는 dangling FK를
        // 만든다(orphan-removal 없는 참조 무결성 위반 시나리오 — batch 2nd hop이 대상을 못 찾는다).
        support.execute("delete from \"key_entity\" where \"id\" = " + k1.getId());

        StepVerifier.create(support.operations().findById(Owner.class, id))
                .assertNext(loaded -> {
                    assertEquals(1, loaded.getTags().size(), "dangling entry는 드롭되지 않고 보존돼야 한다");
                    Map.Entry<KeyEntity, String> entry = loaded.getTags().entrySet().iterator().next();
                    assertEquals(k1.getId(), entry.getKey().getId(), "id-only stub은 @Id 값을 보존해야 한다");
                    assertNull(entry.getKey().getLabel(),
                            "대상 행이 없으므로 @Id 외 필드는 로드되지 않은 채로 남는다(id-only stub)");
                    assertEquals("one", entry.getValue(), "collection table row의 value는 그대로 보존된다");
                })
                .verifyComplete();
    }

    @Test
    void unchangedMapInSessionEmitsNoCollectionTableSqlOnFlush() {
        KeyEntity k1 = support.operations().save(new KeyEntity("alpha")).block();
        Owner owner = new Owner();
        owner.getTags().put(k1, "one");
        Long id = support.operations().save(owner).map(Owner::getId).block();

        listener.clear();
        // 세션 안에서 로드만 하고 Map을 바꾸지 않으면 flush가 collection table을 건드리지 않아야 한다 — 매 로드마다
        // 새 KeyEntity 인스턴스가 만들어짐에도(equals/hashCode 없음) 대표값(@Id)으로 diff 키가 계산돼 baseline과
        // 정확히 일치해야 한다.
        StepVerifier.create(support.operations().inTransaction(ops -> ops.findById(Owner.class, id)))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(0, listener.count("owner_tags", "delete"),
                "변경 없는 entity-key map은 flush에서 collection table SQL을 내지 않아야 한다: " + listener.statements());
        assertEquals(0, listener.count("owner_tags", "insert"),
                "변경 없는 entity-key map은 flush에서 collection table SQL을 내지 않아야 한다: " + listener.statements());
    }

    @Test
    void changedValueInSessionEmitsFullReplaceOnFlush() {
        KeyEntity k1 = support.operations().save(new KeyEntity("alpha")).block();
        Owner owner = new Owner();
        owner.getTags().put(k1, "one");
        Long id = support.operations().save(owner).map(Owner::getId).block();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Owner.class, id)
                                .doOnNext(loaded -> {
                                    // 로드된 map의 실제 key 인스턴스로 value를 바꾼다(entity key는 equals/hashCode가
                                    // 없으므로 새 KeyEntity로 put하면 별개 항목이 된다 — 값 변경 시나리오와는 다르다).
                                    Map.Entry<KeyEntity, String> entry = loaded.getTags().entrySet().iterator().next();
                                    entry.setValue("changed");
                                })
                                .then()))
                .verifyComplete();

        assertTrue(listener.count("owner_tags", "delete") > 0,
                "값이 바뀐 entity-key map은 flush에서 full-replace SQL을 내야 한다: " + listener.statements());
        assertTrue(listener.count("owner_tags", "insert") > 0,
                "값이 바뀐 entity-key map은 flush에서 full-replace SQL을 내야 한다: " + listener.statements());

        StepVerifier.create(support.operations().findById(Owner.class, id))
                .assertNext(reloaded -> {
                    Map.Entry<KeyEntity, String> entry = reloaded.getTags().entrySet().iterator().next();
                    assertEquals("changed", entry.getValue());
                })
                .verifyComplete();
    }

    /** 실행된 SQL 문장을 모아 특정 테이블에 대한 SELECT/INSERT/DELETE 발행 횟수를 세는 listener. */
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

        long selectCountFor(String table) {
            String needle = "from \"" + table + "\"";
            return statements.stream()
                    .map(sql -> sql.toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.startsWith("select"))
                    .filter(sql -> sql.contains(needle))
                    .count();
        }

        long count(String table, String op) {
            return statements.stream()
                    .map(sql -> sql.toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.contains(table) && sql.startsWith(op))
                    .count();
        }
    }

    @Entity
    @Table(name = "key_entity")
    // 의도적으로 equals/hashCode를 오버라이드하지 않는다 — map 재구성/dirty-check가 엔티티 identity가 아니라
    // @Id 값으로 비교됨을 검증하는 것이 이 테스트의 핵심이다.
    public static class KeyEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String label;

        public KeyEntity() {
        }

        public KeyEntity(String label) {
            this.label = label;
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }
    }

    @Entity
    @Table(name = "uuid_key_entity")
    public static class UuidKeyEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        private String label;

        public UuidKeyEntity() {
        }

        public UuidKeyEntity(String label) {
            this.label = label;
        }

        public UUID getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }
    }

    @Embeddable
    public static class Amount {
        private String currency;
        private int cents;

        public Amount() {
        }

        public Amount(String currency, int cents) {
            this.currency = currency;
            this.cents = cents;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Amount that)) {
                return false;
            }
            return cents == that.cents && Objects.equals(currency, that.currency);
        }

        @Override
        public int hashCode() {
            return Objects.hash(currency, cents);
        }
    }

    @Entity
    @Table(name = "owner")
    public static class Owner {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        // 컬렉션만 있는 엔티티는 re-save UPDATE의 SET 절이 비어 부적합하므로 스칼라 컬럼을 하나 둔다.
        private String name = "owner";

        @ElementCollection
        @MapKeyClass(KeyEntity.class)
        @CollectionTable(name = "owner_tags")
        private Map<KeyEntity, String> tags = new HashMap<>();

        @ElementCollection
        @MapKeyClass(KeyEntity.class)
        @CollectionTable(name = "owner_prices")
        private Map<KeyEntity, Amount> prices = new HashMap<>();

        @ElementCollection
        @MapKeyClass(UuidKeyEntity.class)
        @CollectionTable(name = "owner_by_uuid_key")
        private Map<UuidKeyEntity, String> byUuidKey = new HashMap<>();

        public Owner() {
        }

        public Long getId() {
            return id;
        }

        public Map<KeyEntity, String> getTags() {
            return tags;
        }

        public Map<KeyEntity, Amount> getPrices() {
            return prices;
        }

        public Map<UuidKeyEntity, String> getByUuidKey() {
            return byUuidKey;
        }
    }
}
