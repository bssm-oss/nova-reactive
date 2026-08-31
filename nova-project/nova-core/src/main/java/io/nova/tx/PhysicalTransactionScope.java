package io.nova.tx;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reactor Context carrier for resources owned by one physical transaction.
 */
public final class PhysicalTransactionScope {
    public static final String CONTEXT_KEY = "io.nova.tx.physical-transaction-scope";

    private static final PhysicalTransactionScope INACTIVE = new PhysicalTransactionScope(false);

    private final boolean active;
    private final IdentityHashMap<Object, Object> resources = new IdentityHashMap<>();
    private final List<Supplier<Mono<Void>>> beforeCommitCallbacks = new ArrayList<>();
    private final List<Supplier<Mono<Void>>> afterCommitCallbacks = new ArrayList<>();
    private boolean sealed;
    private boolean beforeCommitStarted;
    private boolean afterCommitStarted;

    private PhysicalTransactionScope(boolean active) {
        this.active = active;
    }

    public static Owner newOwner() {
        return new Owner();
    }

    public static PhysicalTransactionScope inactive() {
        return INACTIVE;
    }

    public boolean isActive() {
        return active;
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> T getOrCreateResource(Object key, Supplier<? extends T> factory) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        if (!active) {
            return null;
        }
        if (resources.containsKey(key)) {
            return (T) resources.get(key);
        }
        if (sealed) {
            throw new IllegalStateException("Physical transaction scope is sealed");
        }
        T resource = Objects.requireNonNull(factory.get(), "resource factory must not return null");
        resources.put(key, resource);
        return resource;
    }

    public synchronized void beforeCommit(Supplier<Mono<Void>> callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        if (!active) {
            return;
        }
        if (sealed) {
            throw new IllegalStateException("Physical transaction scope is sealed");
        }
        beforeCommitCallbacks.add(callback);
    }

    /**
     * Registers ordered work that runs exactly once after this physical transaction commits.
     * It is never run for rollback, error, or cancellation paths.
     */
    public synchronized void afterCommit(Supplier<Mono<Void>> callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        if (!active) {
            return;
        }
        if (sealed) {
            throw new IllegalStateException("Physical transaction scope is sealed");
        }
        afterCommitCallbacks.add(callback);
    }

    private synchronized Mono<Void> seal() {
        if (!active || sealed) {
            return Mono.empty();
        }
        sealed = true;
        return Mono.empty();
    }

    private Mono<Void> runBeforeCommit() {
        List<Supplier<Mono<Void>>> callbacks;
        synchronized (this) {
            if (!active || beforeCommitStarted) {
                return Mono.empty();
            }
            beforeCommitStarted = true;
            sealed = true;
            callbacks = List.copyOf(beforeCommitCallbacks);
        }
        return Flux.fromIterable(callbacks)
                .concatMap(callback -> Mono.defer(() ->
                        Objects.requireNonNull(callback.get(), "before-commit callback must not return null")))
                .then();
    }

    private Mono<Void> runAfterCommit() {
        List<Supplier<Mono<Void>>> callbacks;
        synchronized (this) {
            if (!active || afterCommitStarted) {
                return Mono.empty();
            }
            afterCommitStarted = true;
            callbacks = List.copyOf(afterCommitCallbacks);
        }
        return Flux.fromIterable(callbacks)
                .concatMap(callback -> Mono.defer(() ->
                        Objects.requireNonNull(callback.get(), "after-commit callback must not return null")))
                .then();
    }

    /**
     * Manager-owned completion authority. Only the owner instance returned at physical transaction
     * creation can seal and drain its scope; callback code receives {@link #scope()} only.
     */
    public static final class Owner {
        private final PhysicalTransactionScope scope = new PhysicalTransactionScope(true);

        private Owner() {
        }

        public PhysicalTransactionScope scope() {
            return scope;
        }

        public Mono<Void> seal() {
            return scope.seal();
        }

        public Mono<Void> beforeCommit() {
            return scope.runBeforeCommit();
        }

        public Mono<Void> afterCommit() {
            return scope.runAfterCommit();
        }
    }
}
