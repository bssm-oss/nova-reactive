package io.nova.tx;

/**
 * Reactor Context carrier that observes successfully completed SQL write operations.
 *
 * <p>This is intended for transaction implementations that do not expose a
 * {@link PhysicalTransactionScope}. Custom SQL executors and operations can mark the observation after a
 * write-shaped publisher completes successfully; errors and cancellation must not mark it.
 */
public final class TransactionWriteObservation {
    public static final String CONTEXT_KEY = "io.nova.tx.transaction-write-observation";

    private boolean completedWrite;

    /**
     * Records a successfully completed SQL write operation.
     */
    public synchronized void markWriteCompleted() {
        completedWrite = true;
    }

    /**
     * Returns whether a SQL write operation completed successfully.
     */
    public synchronized boolean hasCompletedWrite() {
        return completedWrite;
    }
}
