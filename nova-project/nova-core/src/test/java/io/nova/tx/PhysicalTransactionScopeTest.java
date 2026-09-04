package io.nova.tx;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalTransactionScopeTest {
    @Test
    void resourcesUseIdentityKeys() {
        PhysicalTransactionScope scope = PhysicalTransactionScope.newOwner().scope();
        String firstKey = new String("key");
        String secondKey = new String("key");
        Object first = scope.getOrCreateResource(firstKey, Object::new);
        Object firstAgain = scope.getOrCreateResource(firstKey, Object::new);
        Object second = scope.getOrCreateResource(secondKey, Object::new);

        assertSame(first, firstAgain);
        assertFalse(first == second);
    }

    @Test
    void beforeCommitRunsInOrderOnlyOnce() {
        PhysicalTransactionScope.Owner owner = PhysicalTransactionScope.newOwner();
        PhysicalTransactionScope scope = owner.scope();
        ArrayList<Integer> order = new ArrayList<>();
        scope.beforeCommit(() -> Mono.fromRunnable(() -> order.add(1)));
        scope.beforeCommit(() -> Mono.fromRunnable(() -> order.add(2)));

        owner.beforeCommit().block();
        owner.beforeCommit().block();

        assertEquals(java.util.List.of(1, 2), order);
    }

    @Test
    void afterCommitRunsInOrderOnlyOnceAndPropagatesFailure() {
        PhysicalTransactionScope.Owner owner = PhysicalTransactionScope.newOwner();
        PhysicalTransactionScope scope = owner.scope();
        ArrayList<Integer> order = new ArrayList<>();
        scope.afterCommit(() -> Mono.fromRunnable(() -> order.add(1)));
        scope.afterCommit(() -> Mono.fromRunnable(() -> order.add(2)));

        owner.afterCommit().block();
        owner.afterCommit().block();

        assertEquals(java.util.List.of(1, 2), order);
    }

    @Test
    void sealingRejectsNewResourcesAndCallbacks() {
        PhysicalTransactionScope.Owner owner = PhysicalTransactionScope.newOwner();
        PhysicalTransactionScope scope = owner.scope();
        owner.seal().block();

        assertThrows(IllegalStateException.class, () -> scope.getOrCreateResource(new Object(), Object::new));
        assertThrows(IllegalStateException.class, () -> scope.beforeCommit(Mono::empty));
        assertThrows(IllegalStateException.class, () -> scope.afterCommit(Mono::empty));
    }

    @Test
    void writeMarkerIsMonotonicAndMayBeSetAfterSeal() {
        PhysicalTransactionScope.Owner owner = PhysicalTransactionScope.newOwner();
        PhysicalTransactionScope scope = owner.scope();

        assertFalse(scope.hasCompletedWrite());
        owner.seal().block();
        scope.markWriteCompleted();
        scope.markWriteCompleted();

        assertTrue(scope.hasCompletedWrite());
    }

    @Test
    void inactiveScopeHasNoResourcesOrCallbacks() {
        PhysicalTransactionScope scope = PhysicalTransactionScope.inactive();
        AtomicInteger invoked = new AtomicInteger();

        assertFalse(scope.isActive());
        assertEquals(null, scope.getOrCreateResource(new Object(), Object::new));
        scope.beforeCommit(() -> Mono.fromRunnable(invoked::incrementAndGet));

        assertEquals(0, invoked.get());
        scope.markWriteCompleted();
        assertFalse(scope.hasCompletedWrite());
    }
}
