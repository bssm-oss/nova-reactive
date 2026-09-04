package io.nova.dialect.h2;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.TableGeneratorInfo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class H2SchemaGeneratorTest {
    private final H2Dialect dialect = new H2Dialect();
    private final EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void rendersCreateTableWithIdentityColumnUsingGeneratedAlwaysAsIdentity() {
        EntityMetadata<H2SampleAccount> metadata = metadataFactory.getEntityMetadata(H2SampleAccount.class);

        assertEquals(
                "create table \"accounts\" (\"id\" bigint generated always as identity primary key, \"email_address\" varchar(255), \"active\" boolean not null)",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void rendersCreateTableWithAutoColumnUsingGeneratedAlwaysAsIdentity() {
        EntityMetadata<H2AutoAccount> metadata = metadataFactory.getEntityMetadata(H2AutoAccount.class);

        assertEquals(
                "create table \"auto_accounts\" (\"id\" bigint generated always as identity primary key, \"email_address\" varchar(255))",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void rendersCreateTableForAssignedIdWithoutIdentitySyntax() {
        EntityMetadata<H2AssignedIdAccount> metadata = metadataFactory.getEntityMetadata(H2AssignedIdAccount.class);

        assertEquals(
                "create table \"assigned_accounts\" (\"id\" bigint primary key, \"email_address\" varchar(255))",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void rendersCreateTableGeneratorWithVarcharPkAndBigintCounter() {
        TableGeneratorInfo info = new TableGeneratorInfo(
                "id_generators", "gen_name", "gen_value", "account_id", 100, 5);

        assertEquals(
                "create table \"id_generators\" "
                        + "(\"gen_name\" varchar(255) not null primary key, \"gen_value\" bigint not null)",
                dialect.schemaGenerator().createTableGenerator(info)
        );
    }

    @Test
    void seedsTableGeneratorSoFirstAllocatedIdEqualsInitialValue() {
        TableGeneratorInfo info = new TableGeneratorInfo(
                "id_generators", "gen_name", "gen_value", "account_id", 100, 5);

        // 증가-우선 블록 모델: 카운터는 "다음 발급 첫 id"를 보관하므로 seed = initialValue(100).
        // 첫 발급 시 UPDATE로 105가 되고 블록 [100,104]를 역산하므로 첫 id가 정확히 100이다.
        assertEquals(
                "insert into \"id_generators\" (\"gen_name\", \"gen_value\") values ('account_id', 100)",
                dialect.schemaGenerator().seedTableGenerator(info)
        );
    }
    @Test
    void rendersBigDecimalShapeMatrixWithoutDiscardingScale() {
        EntityMetadata<DecimalShapes> metadata = metadataFactory.getEntityMetadata(DecimalShapes.class);

        assertEquals(
                "create table \"decimal_shapes\" (\"id\" bigint primary key, "
                        + "\"explicit\" numeric(12, 4), "
                        + "\"precision_only\" numeric(10, 0), "
                        + "\"scale_only\" numeric(100000, 3), "
                        + "\"unspecified\" decfloat)",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void acceptsH2MaximumNumericPrecisionAndScale() {
        EntityMetadata<MaximumDecimalShape> metadata = metadataFactory.getEntityMetadata(MaximumDecimalShape.class);

        assertEquals(
                "create table \"maximum_decimal_shape\" (\"id\" bigint primary key, "
                        + "\"amount\" numeric(100000, 100000))",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void rejectsBigDecimalShapesOutsideH2Bounds() {
        assertThrows(IllegalArgumentException.class, () -> createTable(PrecisionTooLarge.class));
        assertThrows(IllegalArgumentException.class, () -> createTable(ScaleTooLarge.class));
        assertThrows(IllegalArgumentException.class, () -> createTable(ScaleExceedsPrecision.class));
        assertThrows(IllegalArgumentException.class, () -> createTable(NegativePrecision.class));
        assertThrows(IllegalArgumentException.class, () -> createTable(NegativeScale.class));
    }

    private String createTable(Class<?> entityType) {
        return dialect.schemaGenerator().createTable(metadataFactory.getEntityMetadata(entityType));
    }

    @Entity
    @Table(name = "decimal_shapes")
    static class DecimalShapes {
        @Id
        Long id;

        @Column(precision = 12, scale = 4)
        BigDecimal explicit;

        @Column(precision = 10)
        BigDecimal precisionOnly;

        @Column(scale = 3)
        BigDecimal scaleOnly;

        BigDecimal unspecified;
    }

    @Entity
    @Table(name = "auto_accounts")
    static class H2AutoAccount {
        @Id
        @GeneratedValue
        Long id;

        @Column(name = "email_address")
        String email;
    }

    @Entity
    @Table(name = "maximum_decimal_shape")
    static class MaximumDecimalShape {
        @Id
        Long id;

        @Column(precision = 100_000, scale = 100_000)
        BigDecimal amount;
    }

    @Entity
    static class PrecisionTooLarge {
        @Id
        Long id;

        @Column(precision = 100_001)
        BigDecimal amount;
    }

    @Entity
    static class ScaleTooLarge {
        @Id
        Long id;

        @Column(scale = 100_001)
        BigDecimal amount;
    }

    @Entity
    static class ScaleExceedsPrecision {
        @Id
        Long id;

        @Column(precision = 4, scale = 5)
        BigDecimal amount;
    }

    @Entity
    static class NegativePrecision {
        @Id
        Long id;

        @Column(precision = -1)
        BigDecimal amount;
    }

    @Entity
    static class NegativeScale {
        @Id
        Long id;

        @Column(scale = -1)
        BigDecimal amount;
    }
}
