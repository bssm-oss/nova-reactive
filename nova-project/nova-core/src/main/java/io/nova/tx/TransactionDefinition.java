package io.nova.tx;

import java.util.Objects;

/**
 * 단일 트랜잭션 경계의 정책을 기술하는 불변 정의다.
 * <p>
 * propagation은 활성 트랜잭션과의 상호작용을, isolation은 격리 수준을,
 * readOnly는 read-only 힌트를 결정한다. 기본값은 {@link #DEFAULT}로,
 * {@link Propagation#REQUIRED} + {@link IsolationLevel#DEFAULT} + readOnly=false다.
 */
public record TransactionDefinition(Propagation propagation, IsolationLevel isolation, boolean readOnly) {
    public static final TransactionDefinition DEFAULT =
            new TransactionDefinition(Propagation.REQUIRED, IsolationLevel.DEFAULT, false);

    public TransactionDefinition {
        Objects.requireNonNull(propagation, "propagation");
        Objects.requireNonNull(isolation, "isolation");
    }

    /** {@link Propagation#REQUIRES_NEW} 정의를 반환한다. */
    public static TransactionDefinition requiresNew() {
        return DEFAULT.with(Propagation.REQUIRES_NEW);
    }

    /** read-only 힌트가 설정된 정의를 반환한다. */
    public static TransactionDefinition asReadOnly() {
        return DEFAULT.withReadOnly(true);
    }

    /** propagation만 변경한 사본을 반환한다. */
    public TransactionDefinition with(Propagation propagation) {
        return new TransactionDefinition(propagation, this.isolation, this.readOnly);
    }

    /** isolation만 변경한 사본을 반환한다. */
    public TransactionDefinition with(IsolationLevel isolation) {
        return new TransactionDefinition(this.propagation, isolation, this.readOnly);
    }

    /** readOnly 힌트만 변경한 사본을 반환한다. */
    public TransactionDefinition withReadOnly(boolean readOnly) {
        return new TransactionDefinition(this.propagation, this.isolation, readOnly);
    }
}
