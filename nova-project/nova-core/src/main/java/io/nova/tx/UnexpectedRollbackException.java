package io.nova.tx;

import jakarta.persistence.RollbackException;

/**
 * Indicates that a participating transaction marked its enclosing boundary rollback-only.
 */
public final class UnexpectedRollbackException extends RollbackException {
    public UnexpectedRollbackException() {
        super("Transaction rolled back because it was marked rollback-only by a participating transaction");
    }
}
