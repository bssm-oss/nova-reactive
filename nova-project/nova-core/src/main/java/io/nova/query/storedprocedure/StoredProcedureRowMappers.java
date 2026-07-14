package io.nova.query.storedprocedure;

import io.nova.core.RowAccessor;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 저장 프로시저 result-set 행을 엔티티로 변환하는 row 매퍼 팩토리. 프로시저는 컬럼 매핑된 엔티티의 모든
 * 컬럼을 select 해야 하며, 각 컬럼은 저장 타입으로 읽은 뒤 property converter/도메인 타입 복원 규칙을 그대로
 * 재사용한다({@link io.nova.query.jpql.NamedQueryRegistry} 및
 * {@link io.nova.query.resultset.SqlResultSetMappingRegistry}의 엔티티 매핑과 동일한 규칙).
 */
public final class StoredProcedureRowMappers {

    private StoredProcedureRowMappers() {
    }

    /** 주어진 엔티티 타입으로 result-set 행을 매핑하는 row 매퍼를 만든다. no-arg 생성자가 없으면 fail-fast. */
    public static <T> Function<RowAccessor, T> entity(EntityMetadataFactory metadataFactory, Class<T> resultClass) {
        Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        Objects.requireNonNull(resultClass, "resultClass must not be null");
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(resultClass);
        List<PersistentProperty> columns = metadata.columnMappedProperties();
        rejectCompositeToOne(resultClass, columns);
        Constructor<T> constructor;
        try {
            constructor = resultClass.getDeclaredConstructor();
            constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new StoredProcedureException("stored procedure resultClass " + resultClass.getName()
                    + " must declare a no-arg constructor for entity mapping", e);
        }
        return row -> {
            T instance;
            try {
                instance = constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new StoredProcedureException(
                        "Failed to instantiate stored procedure resultClass " + resultClass.getName(), e);
            }
            for (PersistentProperty property : columns) {
                Object columnValue = row.get(property.columnName(), property.columnType());
                property.write(instance, property.toPropertyValue(columnValue));
            }
            return instance;
        };
    }

    /**
     * 엔티티 매핑 대상에 복합키({@code @EmbeddedId}/{@code @IdClass}) 타겟을 참조하는 to-one 관계(다중컬럼 FK)가
     * 있으면 fail-fast한다. 이 매퍼는 property당 단일 컬럼만 읽으므로 복합 FK를 조용히 대표 컬럼 하나로만
     * resolve해 오답을 낸다 — 지원 전까지 명확히 거부한다.
     */
    private static void rejectCompositeToOne(Class<?> resultClass, List<PersistentProperty> columns) {
        for (PersistentProperty property : columns) {
            if (property.isCompositeToOne()) {
                throw new StoredProcedureException("stored procedure entity mapping of " + resultClass.getName()
                        + " is not yet supported: association '" + property.propertyName()
                        + "' references a composite-key (@EmbeddedId/@IdClass) target via a multi-column foreign key");
            }
        }
    }
}
