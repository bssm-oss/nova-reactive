package io.nova.r2dbc.integration;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * H2 end-to-end coverage for owning {@link OneToOne#orphanRemoval()}. The cases intentionally use
 * the production transaction wiring: a managed mutation is deferred to the before-commit flush,
 * while an explicit save is the stateless path.
 */
class OneToOneOrphanRemovalIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.createWithManagedTransactions();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Target.class, Owner.class, PropertyTarget.class, PropertyOwner.class,
                CompositeTarget.class, CompositeOwner.class, GuardOwner.class,
                CallbackTarget.class, CallbackOwner.class).block();
        CallbackTarget.failPreRemove.set(false);
        CallbackTarget.preRemoveCalls.set(0);
        CallbackTarget.postRemoveCalls.set(0);
    }

    @Test
    void statelessReplacementAndNullRemoveOldTarget() {
        Target first = save(new Target("first"));
        Owner owner = save(new Owner("owner", first));
        Target second = save(new Target("second"));

        owner.setTarget(second);
        StepVerifier.create(support.operations().save(owner)).expectNext(owner).verifyComplete();
        absent(Target.class, first.getId());
        present(Target.class, second.getId());

        owner.setTarget(null);
        StepVerifier.create(support.operations().save(owner)).expectNext(owner).verifyComplete();
        absent(Target.class, second.getId());
    }

    @Test
    void managedReplacementAndNullRemoveOldTargetAtCommit() {
        Target first = save(new Target("first"));
        Owner owner = save(new Owner("owner", first));
        Target second = save(new Target("second"));

        StepVerifier.create(support.operations().inTransaction(tx -> tx.findById(Owner.class, owner.getId())
                        .doOnNext(loaded -> loaded.setTarget(second))))
                .expectNextCount(1)
                .verifyComplete();
        absent(Target.class, first.getId());
        present(Target.class, second.getId());

        StepVerifier.create(support.operations().inTransaction(tx -> tx.findById(Owner.class, owner.getId())
                        .doOnNext(loaded -> loaded.setTarget(null))))
                .expectNextCount(1)
                .verifyComplete();
        absent(Target.class, second.getId());
    }

    @Test
    void propertyAccessMappingParticipatesInManagedOrphanRemoval() {
        PropertyTarget first = save(new PropertyTarget("first"));
        PropertyOwner owner = save(new PropertyOwner("property", first));
        PropertyTarget second = save(new PropertyTarget("second"));

        StepVerifier.create(support.operations().inTransaction(tx -> tx.findById(PropertyOwner.class, owner.getId())
                        .doOnNext(loaded -> loaded.setTarget(second))))
                .expectNextCount(1)
                .verifyComplete();

        absent(PropertyTarget.class, first.getId());
        present(PropertyTarget.class, second.getId());
    }

    @Test
    void compositeOwnerAndTargetReplacementUpdatesAllForeignKeyColumnsAndDeletesExactOldTarget() {
        CompositeTarget oldTarget = save(new CompositeTarget(new Key("target", 1), "old"));
        CompositeTarget newTarget = save(new CompositeTarget(new Key("target", 2), "new"));
        CompositeOwner owner = save(new CompositeOwner(new Key("owner", 7), oldTarget));

        StepVerifier.create(support.operations().inTransaction(tx -> tx.findById(CompositeOwner.class, owner.getId())
                        .doOnNext(loaded -> loaded.setTarget(newTarget))))
                .expectNextCount(1)
                .verifyComplete();

        absent(CompositeTarget.class, oldTarget.getId());
        StepVerifier.create(support.operations().findById(CompositeOwner.class, owner.getId()))
                .assertNext(reloaded -> {
                    assertNotNull(reloaded.getTarget());
                    assertEquals(newTarget.getId(), reloaded.getTarget().getId(),
                            "both composite FK columns must identify the replacement target");
                })
                .verifyComplete();
        present(CompositeTarget.class, newTarget.getId());
    }

    @Test
    void sameKeyReplacementPreservesTargetRow() {
        Target target = save(new Target("kept"));
        Owner owner = save(new Owner("owner", target));
        Target sameKeyDifferentInstance = new Target("replacement instance");
        sameKeyDifferentInstance.setId(target.getId());

        StepVerifier.create(support.operations().inTransaction(tx -> tx.findById(Owner.class, owner.getId())
                        .doOnNext(loaded -> loaded.setTarget(sameKeyDifferentInstance))))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Target.class, target.getId()))
                .assertNext(reloaded -> assertEquals("kept", reloaded.getLabel()))
                .verifyComplete();
    }

    @Test
    void sharedTargetOnAnotherOwningPropertyIsGuarded() {
        Target target = save(new Target("shared"));
        GuardOwner owner = save(new GuardOwner(target, target));

        StepVerifier.create(support.operations().inTransaction(tx -> tx.findById(GuardOwner.class, owner.getId())
                        .doOnNext(loaded -> loaded.setFirst(null))))
                .expectNextCount(1)
                .verifyComplete();

        present(Target.class, target.getId());
        StepVerifier.create(support.operations().findById(GuardOwner.class, owner.getId()))
                .assertNext(reloaded -> {
                    assertNull(reloaded.getFirst());
                    assertNotNull(reloaded.getSecond());
                    assertEquals(target.getId(), reloaded.getSecond().getId());
                })
                .verifyComplete();
    }

    @Test
    void ownerDeleteWithCascadeRemoveAndOrphanRemovalInvokesTargetCallbacksOnce() {
        CallbackTarget target = save(new CallbackTarget("target"));
        CallbackOwner owner = save(new CallbackOwner(target));

        StepVerifier.create(support.operations().delete(owner)).expectNextCount(1).verifyComplete();

        assertEquals(1, CallbackTarget.preRemoveCalls.get(), "orphan removal and REMOVE cascade share one delete");
        assertEquals(1, CallbackTarget.postRemoveCalls.get(), "target post-remove must not be duplicated");
        absent(CallbackTarget.class, target.getId());
    }

    @Test
    void callbackFailureRollsBackOwnerForeignKeyAndTargetDelete() {
        CallbackTarget target = save(new CallbackTarget("target"));
        CallbackOwner owner = save(new CallbackOwner(target));
        CallbackTarget.failPreRemove.set(true);

        StepVerifier.create(support.operations().inTransaction(tx -> tx.findById(CallbackOwner.class, owner.getId())
                        .doOnNext(loaded -> loaded.setTarget(null))))
                .expectError(IllegalStateException.class)
                .verify();

        CallbackTarget.failPreRemove.set(false);
        StepVerifier.create(support.operations().findById(CallbackOwner.class, owner.getId()))
                .assertNext(reloaded -> {
                    assertNotNull(reloaded.getTarget(), "failed orphan callback must roll back the owner FK update");
                    assertEquals(target.getId(), reloaded.getTarget().getId());
                })
                .verifyComplete();
        present(CallbackTarget.class, target.getId());
        assertEquals(1, CallbackTarget.preRemoveCalls.get());
        assertEquals(0, CallbackTarget.postRemoveCalls.get());
    }

    private <T> T save(T entity) {
        return support.operations().save(entity).block();
    }

    private <T> void present(Class<T> type, Object id) {
        StepVerifier.create(support.operations().findById(type, id)).expectNextCount(1).verifyComplete();
    }

    private <T> void absent(Class<T> type, Object id) {
        StepVerifier.create(support.operations().findById(type, id)).verifyComplete();
    }

    @Entity
    @Table(name = "orphan_target")
    static class Target {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
        private String label;
        Target() { }
        Target(String label) { this.label = label; }
        Long getId() { return id; }
        void setId(Long id) { this.id = id; }
        String getLabel() { return label; }
    }

    @Entity
    @Table(name = "orphan_owner")
    static class Owner {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
        private String label;
        @OneToOne(orphanRemoval = true) @JoinColumn(name = "target_id") private Target target;
        Owner() { }
        Owner(String label, Target target) { this.label = label; this.target = target; }
        Long getId() { return id; }
        Target getTarget() { return target; }
        void setTarget(Target target) { this.target = target; }
    }

    @Entity
    @Table(name = "orphan_property_target")
    static class PropertyTarget {
        private Long id;
        private String label;
        PropertyTarget() { }
        PropertyTarget(String label) { this.label = label; }
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long getId() { return id; }
        void setId(Long id) { this.id = id; }
        String getLabel() { return label; }
        void setLabel(String label) { this.label = label; }
    }

    @Entity
    @Access(AccessType.PROPERTY)
    @Table(name = "orphan_property_owner")
    static class PropertyOwner {
        private Long id;
        private String label;
        private PropertyTarget target;
        PropertyOwner() { }
        PropertyOwner(String label, PropertyTarget target) { this.label = label; this.target = target; }
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long getId() { return id; }
        void setId(Long id) { this.id = id; }
        String getLabel() { return label; }
        void setLabel(String label) { this.label = label; }
        @OneToOne(orphanRemoval = true) @JoinColumn(name = "target_id") PropertyTarget getTarget() { return target; }
        void setTarget(PropertyTarget target) { this.target = target; }
    }

    @Embeddable
    static class Key implements Serializable {
        private String namespace;
        private Integer number;
        Key() { }
        Key(String namespace, Integer number) { this.namespace = namespace; this.number = number; }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(namespace, key.namespace) && Objects.equals(number, key.number);
        }
        @Override public int hashCode() { return Objects.hash(namespace, number); }
    }

    @Entity
    @Table(name = "orphan_composite_target")
    static class CompositeTarget {
        @EmbeddedId private Key id;
        private String label;
        CompositeTarget() { }
        CompositeTarget(Key id, String label) { this.id = id; this.label = label; }
        Key getId() { return id; }
    }

    @Entity
    @Table(name = "orphan_composite_owner")
    static class CompositeOwner {
        @EmbeddedId private Key id;
        @OneToOne(orphanRemoval = true)
        @JoinColumns({@JoinColumn(name = "target_namespace"), @JoinColumn(name = "target_number")})
        private CompositeTarget target;
        CompositeOwner() { }
        CompositeOwner(Key id, CompositeTarget target) { this.id = id; this.target = target; }
        Key getId() { return id; }
        CompositeTarget getTarget() { return target; }
        void setTarget(CompositeTarget target) { this.target = target; }
    }

    @Entity
    @Table(name = "orphan_guard_owner")
    static class GuardOwner {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
        @OneToOne(orphanRemoval = true) @JoinColumn(name = "first_target_id") private Target first;
        @OneToOne(orphanRemoval = true) @JoinColumn(name = "second_target_id") private Target second;
        GuardOwner() { }
        GuardOwner(Target first, Target second) { this.first = first; this.second = second; }
        Long getId() { return id; }
        Target getFirst() { return first; }
        void setFirst(Target first) { this.first = first; }
        Target getSecond() { return second; }
    }

    @Entity
    @Table(name = "orphan_callback_target")
    static class CallbackTarget {
        static final AtomicBoolean failPreRemove = new AtomicBoolean();
        static final AtomicInteger preRemoveCalls = new AtomicInteger();
        static final AtomicInteger postRemoveCalls = new AtomicInteger();
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
        private String label;
        CallbackTarget() { }
        CallbackTarget(String label) { this.label = label; }
        Long getId() { return id; }
        @PreRemove void preRemove() {
            preRemoveCalls.incrementAndGet();
            if (failPreRemove.get()) throw new IllegalStateException("target callback failed");
        }
        @PostRemove void postRemove() { postRemoveCalls.incrementAndGet(); }
    }

    @Entity
    @Table(name = "orphan_callback_owner")
    static class CallbackOwner {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
        @OneToOne(orphanRemoval = true, cascade = CascadeType.REMOVE)
        @JoinColumn(name = "target_id") private CallbackTarget target;
        CallbackOwner() { }
        CallbackOwner(CallbackTarget target) { this.target = target; }
        Long getId() { return id; }
        CallbackTarget getTarget() { return target; }
        void setTarget(CallbackTarget target) { this.target = target; }
    }
}
