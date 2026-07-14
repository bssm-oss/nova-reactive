package io.nova.query.storedprocedure;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.support.fixtures.FixtureEntities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 저장 프로시저 엔티티 row 매퍼는 property당 단일 컬럼만 읽으므로, 복합키 타겟을 참조하는 다중컬럼 FK to-one을
 * 대표 컬럼 하나로만 resolve해 오답을 낸다. 지원 전까지 매퍼 생성 시점에 fail-fast하는지 검증한다.
 */
class StoredProcedureRowMappersCompositeTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void entityMapperOverCompositeToOneFailsFast() {
        StoredProcedureException ex = assertThrows(StoredProcedureException.class,
                () -> StoredProcedureRowMappers.entity(factory, FixtureEntities.CompositeJoinChild.class));
        assertTrue(ex.getMessage().contains("composite-key"));
    }
}
