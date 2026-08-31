package io.nova.r2dbc.integration;

import io.nova.r2dbc.integration.IntegrationFixtures.IdentityAccount;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

/**
 * {@code SchemaInitializer.validate(...)}가 production H2Dialect의 catalog 조회
 * ({@code information_schema.tables})로 테이블 존재 여부를 검증하는 end-to-end 동작을 보호한다.
 * dialect 식별자 case-folding(H2는 대문자 저장)이 case-insensitive 비교로 흡수되는지도 함께 확인한다.
 */
class SchemaInitializerValidateIntegrationTest {

    private SchemaInitializer schemaInitializer(H2IntegrationTestSupport support) {
        return new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
    }

    @Test
    void validateErrorsWhenTableMissing() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        SchemaInitializer schema = schemaInitializer(support);

        StepVerifier.create(schema.validate(List.of(IdentityAccount.class)))
                .verifyErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("identity_accounts")
                        && error.getMessage().contains("is missing"));
    }

    @Test
    void validateCompletesWhenTableExists() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        SchemaInitializer schema = schemaInitializer(support);

        schema.create(IdentityAccount.class).block();

        StepVerifier.create(schema.validate(List.of(IdentityAccount.class)))
                .verifyComplete();
    }

    @Test
    void validateErrorsWhenColumnMissing() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        SchemaInitializer schema = schemaInitializer(support);

        // 테이블은 있지만 entity의 "active" 컬럼이 빠져 있다.
        support.execute("create table \"identity_accounts\" ("
                + "\"id\" bigint primary key, \"email_address\" varchar(255))");

        StepVerifier.create(schema.validate(List.of(IdentityAccount.class)))
                .verifyErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("missing columns")
                        && error.getMessage().contains("active"));
    }

    @Test
    void validateCompletesForJoinedHierarchyPhysicalTables() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        SchemaInitializer schema = schemaInitializer(support);

        schema.create(InheritanceJoinedIntegrationTest.JVehicle.class,
                InheritanceJoinedIntegrationTest.JCar.class,
                InheritanceJoinedIntegrationTest.JTruck.class).block();

        StepVerifier.create(schema.validate(List.of(InheritanceJoinedIntegrationTest.JVehicle.class,
                        InheritanceJoinedIntegrationTest.JCar.class,
                        InheritanceJoinedIntegrationTest.JTruck.class)))
                .verifyComplete();
    }

    @Test
    void validateReportsMissingJoinedSubtypeTableAndColumn() {
        H2IntegrationTestSupport missingTableSupport = H2IntegrationTestSupport.create();
        SchemaInitializer missingTableSchema = schemaInitializer(missingTableSupport);
        missingTableSchema.create(InheritanceJoinedIntegrationTest.JVehicle.class,
                InheritanceJoinedIntegrationTest.JCar.class,
                InheritanceJoinedIntegrationTest.JTruck.class).block();
        missingTableSupport.execute("drop table \"j_car\"");

        StepVerifier.create(missingTableSchema.validate(List.of(InheritanceJoinedIntegrationTest.JVehicle.class,
                        InheritanceJoinedIntegrationTest.JCar.class,
                        InheritanceJoinedIntegrationTest.JTruck.class)))
                .verifyErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("table 'j_car' is missing"));

        H2IntegrationTestSupport missingColumnSupport = H2IntegrationTestSupport.create();
        SchemaInitializer missingColumnSchema = schemaInitializer(missingColumnSupport);
        missingColumnSchema.create(InheritanceJoinedIntegrationTest.JVehicle.class,
                InheritanceJoinedIntegrationTest.JCar.class,
                InheritanceJoinedIntegrationTest.JTruck.class).block();
        missingColumnSupport.execute("alter table \"j_car\" drop column \"doors\"");

        StepVerifier.create(missingColumnSchema.validate(List.of(InheritanceJoinedIntegrationTest.JVehicle.class,
                        InheritanceJoinedIntegrationTest.JCar.class,
                        InheritanceJoinedIntegrationTest.JTruck.class)))
                .verifyErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("table 'j_car' is missing columns [doors]"));
    }

    @Test
    void validateCompletesForTablePerClassHierarchyPhysicalTables() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        SchemaInitializer schema = schemaInitializer(support);

        schema.create(InheritanceTablePerClassIntegrationTest.TVehicle.class,
                InheritanceTablePerClassIntegrationTest.TCar.class,
                InheritanceTablePerClassIntegrationTest.TTruck.class).block();

        StepVerifier.create(schema.validate(List.of(InheritanceTablePerClassIntegrationTest.TVehicle.class,
                        InheritanceTablePerClassIntegrationTest.TCar.class,
                        InheritanceTablePerClassIntegrationTest.TTruck.class)))
                .verifyComplete();
    }

    @Test
    void validateReportsMissingTablePerClassSubtypeTableAndColumn() {
        H2IntegrationTestSupport missingTableSupport = H2IntegrationTestSupport.create();
        SchemaInitializer missingTableSchema = schemaInitializer(missingTableSupport);
        missingTableSchema.create(InheritanceTablePerClassIntegrationTest.TVehicle.class,
                InheritanceTablePerClassIntegrationTest.TCar.class,
                InheritanceTablePerClassIntegrationTest.TTruck.class).block();
        missingTableSupport.execute("drop table \"t_car\"");

        StepVerifier.create(missingTableSchema.validate(List.of(InheritanceTablePerClassIntegrationTest.TVehicle.class,
                        InheritanceTablePerClassIntegrationTest.TCar.class,
                        InheritanceTablePerClassIntegrationTest.TTruck.class)))
                .verifyErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("table 't_car' is missing"));

        H2IntegrationTestSupport missingColumnSupport = H2IntegrationTestSupport.create();
        SchemaInitializer missingColumnSchema = schemaInitializer(missingColumnSupport);
        missingColumnSchema.create(InheritanceTablePerClassIntegrationTest.TVehicle.class,
                InheritanceTablePerClassIntegrationTest.TCar.class,
                InheritanceTablePerClassIntegrationTest.TTruck.class).block();
        missingColumnSupport.execute("alter table \"t_car\" drop column \"doors\"");

        StepVerifier.create(missingColumnSchema.validate(List.of(InheritanceTablePerClassIntegrationTest.TVehicle.class,
                        InheritanceTablePerClassIntegrationTest.TCar.class,
                        InheritanceTablePerClassIntegrationTest.TTruck.class)))
                .verifyErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("table 't_car' is missing columns [doors]"));
    }
}
