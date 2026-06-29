package io.nova.example.hibernate;

import io.smallrye.mutiny.Uni;
import jakarta.persistence.Persistence;
import org.hibernate.LazyInitializationException;
import org.hibernate.reactive.mutiny.Mutiny;

import java.math.BigDecimal;
import java.util.List;

/**
 * Hibernate Reactive (Mutiny) equivalent of {@link io.nova.example.nova.NovaReactiveExample}.
 *
 * <p>Note the friction the same scenario costs us here:
 * <ol>
 *   <li>{@code META-INF/persistence.xml} must declare the persistence unit
 *       (DB url, driver, dialect, entity list) — config split from code.</li>
 *   <li>{@code Persistence.createEntityManagerFactory(...).unwrap(...)} to reach
 *       the reactive {@link Mutiny.SessionFactory}.</li>
 *   <li>Every operation must run inside {@code withSession}/{@code withTransaction} —
 *       the session boundary is part of the call shape, not a Reactor Context.</li>
 *   <li>{@code user.getOrders()} returns a LAZY proxy. Touching it without
 *       {@link Mutiny#fetch(Object)} throws {@link LazyInitializationException}
 *       (or fails the reactive chain).</li>
 *   <li>The reactive type is Mutiny {@link Uni}, not Reactor {@code Mono} —
 *       composing with the rest of a Reactor pipeline needs an adapter.</li>
 * </ol>
 *
 * Run (requires PostgreSQL on localhost:5432, database "example", user/pass "example"):
 *   ./gradlew :nova-project:nova-example:runHibernate
 */
public final class HibernateReactiveExample {

    public static void main(String[] args) {
        // 2. Persistence-unit lookup → reactive SessionFactory unwrap.
        Mutiny.SessionFactory sessionFactory = Persistence
                .createEntityManagerFactory("example-pu")
                .unwrap(Mutiny.SessionFactory.class);

        try {
            // 3. Mandatory session/transaction boundary. The closure is the
            //    only place where lazy collections may be fetched.
            Long userId = sessionFactory.withTransaction((session, tx) -> {
                User user = new User("Hyunwoo");
                Order o1 = new Order("MacBook", new BigDecimal("3200.00"));
                Order o2 = new Order("Keyboard", new BigDecimal("180.00"));
                Order o3 = new Order("Coffee",   new BigDecimal("4.50"));
                user.addOrder(o1);
                user.addOrder(o2);
                user.addOrder(o3);
                return session.persist(user)
                        .chain(session::flush)
                        .replaceWith(() -> user.getId());
            }).await().indefinitely();

            // 3+4. Re-open a session just to read. Even after find(), the
            //      orders collection is a lazy proxy: we have to ask Mutiny.fetch
            //      to materialize it before the session closes.
            sessionFactory.withSession(session ->
                    session.find(User.class, userId)
                            .chain(user -> Mutiny.fetch(user.getOrders())
                                    .map(orders -> {
                                        BigDecimal total = orders.stream()
                                                .map(Order::getAmount)
                                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                                        System.out.printf("[Hibernate Reactive] %s has %d orders, total=%s%n",
                                                user.getName(), orders.size(), total);
                                        return user;
                                    }))
                            // Forgetting the line above and accessing user.getOrders()
                            // outside the session would explode:
                            //   throw new LazyInitializationException(
                            //       "Collection 'User.orders' cannot be initialized — Session has been closed");
                            .invoke(user -> demonstrateLazyPitfall(user.getOrders()))
            ).await().indefinitely();
        } finally {
            sessionFactory.close();
        }
    }

    private static void demonstrateLazyPitfall(List<Order> orders) {
        try {
            orders.size(); // safe only because we fetched above
        } catch (LazyInitializationException e) {
            System.err.println("[Hibernate Reactive] LazyInitializationException — "
                    + "this is the trap Mutiny.fetch() exists to avoid.");
        }
    }
}
