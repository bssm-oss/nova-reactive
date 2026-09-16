package io.nova.boot;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Wrapped;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.connection.ConnectionHolder;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Adapts Nova's plain R2DBC connection lifecycle to a Spring-managed reactive transaction.
 * Transaction-bound connections are exposed through close-suppressing handles because Spring's
 * {@code R2dbcTransactionManager}, rather than an individual Nova operation, owns their commit,
 * rollback, and close lifecycle. Outside a Spring transaction this factory delegates unchanged.
 */
final class SpringTransactionAwareConnectionFactory implements ConnectionFactory {
    private final ConnectionFactory target;

    SpringTransactionAwareConnectionFactory(ConnectionFactory target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public Publisher<? extends Connection> create() {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .flatMap(synchronizationManager -> transactionConnection(synchronizationManager)
                        .map(SpringTransactionAwareConnectionFactory::closeSuppressingConnection)
                        .switchIfEmpty(Mono.from(target.create())))
                .onErrorResume(NoTransactionException.class, ignored -> Mono.from(target.create()));
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        return target.getMetadata();
    }

    private Mono<Connection> transactionConnection(TransactionSynchronizationManager synchronizationManager) {
        Object resource = synchronizationManager.getResource(target);
        if (!(resource instanceof ConnectionHolder holder)) {
            return Mono.empty();
        }
        return Mono.fromSupplier(holder::getConnection);
    }

    private static Connection closeSuppressingConnection(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class, Wrapped.class},
                new CloseSuppressingInvocationHandler(target));
    }

    private static final class CloseSuppressingInvocationHandler implements java.lang.reflect.InvocationHandler {
        private final Connection target;
        private boolean closed;

        private CloseSuppressingInvocationHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Spring transaction-aware Nova connection for " + target;
                    default -> method.invoke(this, arguments);
                };
            }
            return switch (method.getName()) {
                case "close" -> Mono.fromRunnable(() -> closed = true);
                case "isClosed" -> closed;
                case "unwrap" -> target;
                default -> invokeTarget(method, arguments);
            };
        }

        private Object invokeTarget(Method method, Object[] arguments) throws Throwable {
            if (closed) {
                throw new IllegalStateException("Connection handle already closed");
            }
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getTargetException();
            }
        }
    }
}
