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
                        && error.getMessage().contains("missing tables")
                        && error.getMessage().contains("identity_accounts"));
    }

    @Test
    void validateCompletesWhenTableExists() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        SchemaInitializer schema = schemaInitializer(support);

        schema.create(IdentityAccount.class).block();

        StepVerifier.create(schema.validate(List.of(IdentityAccount.class)))
                .verifyComplete();
    }
}
