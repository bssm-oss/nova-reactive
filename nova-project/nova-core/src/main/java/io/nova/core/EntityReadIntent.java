package io.nova.core;

/**
 * Marks a narrowly scoped entity read in Reactor {@code Context}.
 *
 * <p>{@link #REFRESH} makes cache decorators delegate the read directly so a refresh observes the
 * database without reading from or populating a shared cache.
 */
public enum EntityReadIntent {
    /**
     * A fresh read performed by {@link ReactiveEntityManager#refresh(Object)} or
     * {@link ReactiveEntityManager#refresh(Object, jakarta.persistence.LockModeType)}.
     */
    REFRESH
}
