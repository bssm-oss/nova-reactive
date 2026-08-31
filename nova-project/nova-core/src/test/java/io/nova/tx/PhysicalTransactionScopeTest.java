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
        PhysicalTransactionScope scope = PhysicalTransactionScope.active();
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
        PhysicalTransactionScope scope = PhysicalTransactionScope.active();
        ArrayList<Integer> order = new ArrayList<>();
        scope.beforeCommit(() -> Mono.fromRunnable(() -> order.add(1)));
        scope.beforeCommit(() -> Mono.fromRunnable(() -> order.add(2)));

        scope.beforeCommit().block();
        scope.beforeCommit().block();

        assertEquals(java.util.List.of(1, 2), order);
    }

    @Test
    void sealingRejectsNewResourcesAndCallbacks() {
        PhysicalTransactionScope scope = PhysicalTransactionScope.active();
        scope.seal().block();

        assertThrows(IllegalStateException.class, () -> scope.getOrCreateResource(new Object(), Object::new));
        assertThrows(IllegalStateException.class, () -> scope.beforeCommit(Mono::empty));
    }

    @Test
    void inactiveScopeHasNoResourcesOrCallbacks() {
        PhysicalTransactionScope scope = PhysicalTransactionScope.inactive();
        AtomicInteger invoked = new AtomicInteger();

        assertFalse(scope.isActive());
        assertEquals(null, scope.getOrCreateResource(new Object(), Object::new));
        scope.beforeCommit(() -> Mono.fromRunnable(invoked::incrementAndGet));
        scope.beforeCommit().block();

        assertEquals(0, invoked.get());
    }
}
