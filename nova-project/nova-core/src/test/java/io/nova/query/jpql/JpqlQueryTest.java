package io.nova.query.jpql;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpqlQueryTest {

    private final JpqlEntityResolver resolver = new JpqlEntityResolver(
            new EntityMetadataFactory(new DefaultNamingStrategy()), List.of(Employee.class));
    private final JpqlSqlBuilder builder = new JpqlSqlBuilder(new TestDialect(), resolver);
    private final ReactiveEntityOperations operations = (ReactiveEntityOperations) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {ReactiveEntityOperations.class},
            (proxy, method, arguments) -> {
                throw new AssertionError("Pagination fail-fast must not execute " + method.getName());
            });

    @Test
    void rejectsPaginationForScalarAggregateAndConstructorProjections() {
        assertProjectionPaginationFails("SELECT e.name FROM Employee e", query -> query.setMaxResults(5));
        assertProjectionPaginationFails("SELECT COUNT(e) FROM Employee e", query -> query.setMaxResults(5));
        assertProjectionPaginationFails(
                "SELECT NEW example.EmployeeDto(e.name) FROM Employee e", query -> query.setMaxResults(5));
    }

    @Test
    void rejectsOffsetOnlyForEveryProjectionLane() {
        assertProjectionPaginationFails("SELECT e.name FROM Employee e", query -> query.setFirstResult(2));
        assertProjectionPaginationFails("SELECT COUNT(e) FROM Employee e", query -> query.setFirstResult(2));
        assertProjectionPaginationFails(
                "SELECT NEW example.EmployeeDto(e.name) FROM Employee e", query -> query.setFirstResult(2));
    }

    @Test
    void maxResultsZeroDoesNotBypassProjectionPaginationContract() {
        assertProjectionPaginationFails("SELECT e.name FROM Employee e", query -> query.setMaxResults(0));
    }

    @Test
    void paginationRejectsNegativeValuesBeforeSqlConstruction() {
        JpqlQuery<Object> query = query("SELECT e.name FROM Employee e");

        assertThrows(JpqlException.class, () -> query.setFirstResult(-1));
        assertThrows(JpqlException.class, () -> query.setMaxResults(-1));
    }

    @Test
    void doesNotAppendOrExecuteInjectedParameterAsPaginationSql() {
        JpqlQuery<Object> query = query("SELECT e.name FROM Employee e WHERE e.name = :name")
                .setParameter("name", "'; drop table employee; --")
                .setMaxResults(5);

        StepVerifier.create(query.getResultList())
                .expectErrorSatisfies(error -> assertTrue(error.getMessage().contains(
                        "dialect-owned arbitrary-SELECT pagination renderer")))
                .verify();
    }

    private void assertProjectionPaginationFails(String jpql, java.util.function.Consumer<JpqlQuery<Object>> configure) {
        JpqlQuery<Object> query = query(jpql);
        configure.accept(query);

        StepVerifier.create(query.getResultList())
                .expectErrorSatisfies(error -> assertTrue(error.getMessage().contains(
                        "dialect-owned arbitrary-SELECT pagination renderer")))
                .verify();
    }

    private JpqlQuery<Object> query(String jpql) {
        return new JpqlQuery<>(
                (JpqlStatement.Select) new JpqlParser(jpql).parse(),
                Object.class,
                operations,
                builder,
                new JpqlEntityQueryPlanner(resolver));
    }

    @Entity
    @Table(name = "employee")
    public static class Employee {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;
    }

    private static final class TestDialect implements Dialect {
        @Override
        public String name() {
            return "test";
        }

        @Override
        public String quote(String identifier) {
            return "\"" + identifier + "\"";
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return index -> "?";
        }

        @Override
        public SqlRenderer sqlRenderer() {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            throw new UnsupportedOperationException("not needed");
        }
    }
}
