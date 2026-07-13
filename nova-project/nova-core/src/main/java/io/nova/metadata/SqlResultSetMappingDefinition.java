package io.nova.metadata;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code @SqlResultSetMapping} 한 건을 애너테이션에서 분리한 immutable 선언 모델.
 * {@link EntityMetadataFactory#sqlResultSetMappings(Class)}가 파싱해 발행하고, native 쿼리 결과를 엔티티/DTO/
 * 스칼라로 매핑하는 registry가 이름으로 등록·실행한다. jakarta 애너테이션 타입에 직접 의존하지 않는
 * 값 객체이므로 실행 계층이 리플렉션 애너테이션을 다시 읽을 필요가 없다.
 * <p>
 * 매핑 결과는 선언 순서대로 {@link #entities()} → {@link #classes()} → {@link #columns()}로 조립된다. 총
 * 결과 원소가 하나면 그 값 자체를, 둘 이상이면 {@code Object[]}를 row당 발행한다.
 *
 * @param name     전역 고유 매핑 이름
 * @param entities {@code @EntityResult} 목록(엔티티 매핑)
 * @param classes  {@code @ConstructorResult} 목록(DTO 생성자 매핑)
 * @param columns  {@code @ColumnResult} 목록(스칼라 컬럼 매핑)
 */
public record SqlResultSetMappingDefinition(
        String name,
        List<EntityMapping> entities,
        List<ConstructorMapping> classes,
        List<ColumnMapping> columns) {

    public SqlResultSetMappingDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("result set mapping name must not be blank");
        }
        entities = List.copyOf(Objects.requireNonNull(entities, "entities must not be null"));
        classes = List.copyOf(Objects.requireNonNull(classes, "classes must not be null"));
        columns = List.copyOf(Objects.requireNonNull(columns, "columns must not be null"));
        if (entities.isEmpty() && classes.isEmpty() && columns.isEmpty()) {
            throw new IllegalStateException("@SqlResultSetMapping '" + name
                    + "' must declare at least one of entities, classes, or columns");
        }
    }

    /** 이 매핑이 발행하는 결과 원소 개수(entities + classes + columns). */
    public int resultElementCount() {
        return entities.size() + classes.size() + columns.size();
    }

    /**
     * {@code @EntityResult} 한 건. row를 {@code entityClass}로 매핑하며, {@code fieldAliases}는
     * {@code @FieldResult}로 지정된 엔티티 속성명 → 컬럼 별칭 매핑이다. 지정되지 않은 속성은 엔티티의 기본
     * 컬럼명을 별칭으로 사용한다.
     *
     * @param entityClass  매핑 대상 엔티티 타입
     * @param fieldAliases 엔티티 속성명 → 컬럼 별칭(선언 순서 보존, 없으면 빈 맵)
     */
    public record EntityMapping(Class<?> entityClass, Map<String, String> fieldAliases) {
        public EntityMapping {
            Objects.requireNonNull(entityClass, "entityClass must not be null");
            fieldAliases = Map.copyOf(Objects.requireNonNull(fieldAliases, "fieldAliases must not be null"));
        }
    }

    /**
     * {@code @ConstructorResult} 한 건. {@code columns}의 값을 순서대로 {@code targetClass}의 생성자 인자로
     * 넘겨 DTO 인스턴스를 만든다.
     *
     * @param targetClass DTO 타입
     * @param columns     생성자 인자에 매핑되는 컬럼 목록(순서 유의미)
     */
    public record ConstructorMapping(Class<?> targetClass, List<ColumnMapping> columns) {
        public ConstructorMapping {
            Objects.requireNonNull(targetClass, "targetClass must not be null");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns must not be null"));
        }
    }

    /**
     * {@code @ColumnResult} 한 건. {@code column}은 SELECT 결과의 컬럼 별칭이며, {@code type}이 지정되면 그
     * 타입으로 강제 변환(coercion)한다. {@code type}이 {@code null}이면 driver가 반환하는 raw 타입을 그대로
     * 노출한다({@code Object}).
     *
     * @param column 결과 컬럼 별칭
     * @param type   변환 대상 타입(선택). 없으면 {@code null}
     */
    public record ColumnMapping(String column, Class<?> type) {
        public ColumnMapping {
            Objects.requireNonNull(column, "column must not be null");
            if (column.isBlank()) {
                throw new IllegalArgumentException("@ColumnResult column must not be blank");
            }
        }
    }
}
