package io.nova.metadata;

import jakarta.persistence.EnumType;
import jakarta.persistence.GenerationType;
import io.nova.convert.AttributeConverter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

public final class PersistentProperty {
    private final Field field;
    private final String propertyName;
    private final String columnName;
    private final Class<?> javaType;
    private final boolean id;
    private final boolean version;
    private final boolean nullable;
    private final int length;
    private final int precision;
    private final int scale;
    private final GenerationType generationType;
    private final String generator;
    private final AttributeConverter<Object, Object> converter;
    private final boolean createdAt;
    private final boolean updatedAt;
    private final boolean softDelete;
    private final boolean embedded;
    /**
     * 호스트 엔티티 인스턴스에서 이 property가 가리키는 leaf field까지 traverse해야 하는
     * {@link jakarta.persistence.Embedded} 필드들의 outer → inner 순서 체인. top-level property는
     * 비어있다. nested 1-level은 길이 1, 2-level은 길이 2.
     */
    private final List<Field> embeddedHostPath;
    private final boolean enumerated;
    private final EnumType enumType;
    private final boolean json;
    private final boolean manyToOne;
    private final Class<?> manyToOneTargetType;
    private final boolean manyToOneNullable;
    private final boolean oneToMany;
    private final Class<?> oneToManyTargetType;
    private final String oneToManyMappedBy;
    private final boolean insertable;
    private final boolean updatable;
    private final boolean unique;
    private final String columnDefinition;

    @SuppressWarnings("unchecked")
    public PersistentProperty(
            Field field,
            String propertyName,
            String columnName,
            Class<?> javaType,
            boolean id,
            boolean version,
            boolean nullable,
            int length,
            int precision,
            int scale,
            GenerationType generationType,
            String generator,
            AttributeConverter<?, ?> converter,
            boolean createdAt,
            boolean updatedAt,
            boolean softDelete,
            boolean embedded,
            List<Field> embeddedHostPath,
            boolean enumerated,
            EnumType enumType,
            boolean json,
            boolean manyToOne,
            Class<?> manyToOneTargetType,
            boolean manyToOneNullable,
            boolean oneToMany,
            Class<?> oneToManyTargetType,
            String oneToManyMappedBy,
            boolean insertable,
            boolean updatable,
            boolean unique,
            String columnDefinition
    ) {
        this.field = field;
        this.field.setAccessible(true);
        this.propertyName = propertyName;
        this.columnName = columnName;
        this.javaType = javaType;
        this.id = id;
        this.version = version;
        this.nullable = nullable;
        this.length = length;
        this.precision = precision;
        this.scale = scale;
        this.generationType = generationType;
        this.generator = generator == null ? "" : generator;
        this.converter = (AttributeConverter<Object, Object>) converter;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.softDelete = softDelete;
        this.embedded = embedded;
        this.embeddedHostPath = embeddedHostPath == null ? List.of() : List.copyOf(embeddedHostPath);
        for (Field hostField : this.embeddedHostPath) {
            hostField.setAccessible(true);
        }
        this.enumerated = enumerated;
        this.enumType = enumType;
        this.json = json;
        this.manyToOne = manyToOne;
        this.manyToOneTargetType = manyToOneTargetType;
        this.manyToOneNullable = manyToOneNullable;
        this.oneToMany = oneToMany;
        this.oneToManyTargetType = oneToManyTargetType;
        this.oneToManyMappedBy = oneToManyMappedBy == null ? "" : oneToManyMappedBy;
        this.insertable = insertable;
        this.updatable = updatable;
        this.unique = unique;
        this.columnDefinition = columnDefinition == null ? "" : columnDefinition;
    }

    /**
     * INSERT 절에 이 컬럼을 바인딩할지 여부. {@code @Column(insertable=false)}이면 {@code false}.
     */
    public boolean insertable() {
        return insertable;
    }

    /**
     * UPDATE 절에 이 컬럼을 바인딩할지 여부. {@code @Column(updatable=false)}이면 {@code false}.
     */
    public boolean updatable() {
        return updatable;
    }

    /**
     * 컬럼에 UNIQUE 제약을 둘지 여부. {@code @Column(unique=true)}이면 {@code true}.
     */
    public boolean unique() {
        return unique;
    }

    /**
     * {@code @Column(columnDefinition=...)}로 지정된 raw 컬럼 DDL 조각. 비어 있으면 dialect가
     * 필드 타입에서 컬럼 타입을 유도한다.
     */
    public String columnDefinition() {
        return columnDefinition;
    }

    public Field field() {
        return field;
    }

    public String propertyName() {
        return propertyName;
    }

    public String columnName() {
        return columnName;
    }

    public Class<?> javaType() {
        return javaType;
    }

    public boolean id() {
        return id;
    }

    public boolean version() {
        return version;
    }

    public boolean nullable() {
        return nullable;
    }

    /**
     * varchar 등 가변 길이 문자열 컬럼의 길이. {@link jakarta.persistence.Column#length()}에서 오며
     * 기본값은 255다. {@link io.nova.sql.AbstractSchemaGenerator}가 {@code varchar(length)}를 emit할 때 쓴다.
     */
    public int length() {
        return length;
    }

    /**
     * {@link java.math.BigDecimal} numeric 컬럼의 전체 자릿수. {@code 0}이면 미지정이며 dialect가
     * 합리적 기본값을 적용한다. {@link jakarta.persistence.Column#precision()}에서 온다.
     */
    public int precision() {
        return precision;
    }

    /**
     * {@link java.math.BigDecimal} numeric 컬럼의 소수 자릿수. {@link jakarta.persistence.Column#scale()}에서 온다.
     */
    public int scale() {
        return scale;
    }

    public GenerationType generationType() {
        return generationType;
    }

    public String generator() {
        return generator;
    }

    public boolean generated() {
        return generationType != null;
    }

    public boolean createdAt() {
        return createdAt;
    }

    public boolean updatedAt() {
        return updatedAt;
    }

    public boolean softDelete() {
        return softDelete;
    }

    /**
     * {@code true}이면 이 property는 호스트 엔티티의 {@link jakarta.persistence.Embedded}
     * 필드에 위치한 {@link jakarta.persistence.Embeddable} 타입에서 펼쳐져 나온 것이다.
     */
    public boolean embedded() {
        return embedded;
    }

    /**
     * 이 property의 값을 read/write할 때 먼저 거쳐야 하는 호스트 엔티티의
     * {@link jakarta.persistence.Embedded} 필드. top-level property는 {@code null}.
     * 다단계 nested embedded일 때는 가장 안쪽(leaf field를 직접 담는) 호스트 필드를 반환한다.
     * 전체 chain은 {@link #embeddedHostPath()}를 사용한다.
     */
    public Field embeddedHostField() {
        if (embeddedHostPath.isEmpty()) {
            return null;
        }
        return embeddedHostPath.get(embeddedHostPath.size() - 1);
    }

    /**
     * 호스트 엔티티 인스턴스에서 leaf field까지 도달하기 위해 outer → inner 순서로 거쳐야 하는
     * {@link jakarta.persistence.Embedded} 호스트 필드 체인. top-level property는 빈 리스트를 반환한다.
     * 1-level embedded는 길이 1, 2-level nested embedded는 길이 2.
     */
    public List<Field> embeddedHostPath() {
        return embeddedHostPath;
    }

    /**
     * {@code true}이면 이 property는 {@link jakarta.persistence.Enumerated}로 마킹된 enum 필드이며
     * {@link #enumType()}이 {@link jakarta.persistence.EnumType#STRING} 또는 {@code ORDINAL} 중 하나를
     * 반환한다.
     */
    public boolean enumerated() {
        return enumerated;
    }

    /**
     * enumerated property의 저장 전략. enum이 아닌 property는 {@code null}이다.
     */
    public EnumType enumType() {
        return enumType;
    }

    /**
     * {@code true}이면 이 property는 {@link io.nova.annotation.Json}으로 마킹되어 값이 JSON 문자열로
     * 직렬화돼 단일 컬럼에 저장된다. 컬럼 SQL 타입은 {@link io.nova.sql.Dialect#jsonColumnType()}이
     * 결정하고, 값 변환은 {@link io.nova.convert.JsonAttributeConverter}를 통한 일반 converter 경로로 흐른다.
     */
    public boolean json() {
        return json;
    }

    /**
     * row 디코딩 시 R2DBC driver에 요청해야 하는 컬럼 값의 Java 타입을 반환한다.
     * <p>
     * converter가 적용되는 property는 driver가 디코딩할 수 있는 <em>저장 표현 타입</em>을 요청해야 한다 —
     * driver는 {@code varchar} 컬럼을 enum 클래스나 임의 POJO로 직접 디코딩할 수 없기 때문이다. row에서
     * 저장 타입을 읽은 뒤 {@link #toPropertyValue(Object)}가 도메인 타입으로 복원한다. 구체적으로
     * {@code @Json}과 {@code @Enumerated(STRING)}은 {@link String}, {@code @Enumerated(ORDINAL)}은
     * {@link Integer}로 저장된다. converter가 없으면 도메인 타입({@link #javaType()})을 그대로 요청한다.
     * <p>
     * 이 타입은 {@link io.nova.sql.AbstractSchemaGenerator}가 emit하는 컬럼 SQL 타입과 짝을 이룬다
     * (STRING/json → {@code varchar}, ORDINAL → {@code integer}).
     */
    public Class<?> columnType() {
        if (json) {
            return String.class;
        }
        if (enumerated) {
            return enumType == EnumType.STRING ? String.class : Integer.class;
        }
        return javaType;
    }

    /**
     * {@code true}이면 이 property는 {@link jakarta.persistence.ManyToOne}의 owning side이며,
     * {@link #columnName()}은 FK 컬럼 이름, {@link #manyToOneTargetType()}는 참조 대상 entity 타입이다.
     * column read/write 시에는 child entity의 id 값을 직접 다룬다 — 이 property에서 entity 인스턴스를
     * 자동으로 다루지는 않는다.
     */
    public boolean manyToOne() {
        return manyToOne;
    }

    public Class<?> manyToOneTargetType() {
        return manyToOneTargetType;
    }

    /**
     * {@link jakarta.persistence.ManyToOne#optional()}와 {@link jakarta.persistence.JoinColumn#nullable()}
     * 중 더 strict한 값. {@code false}이면 FK 컬럼은 NOT NULL.
     */
    public boolean manyToOneNullable() {
        return manyToOneNullable;
    }

    /**
     * {@code true}이면 이 property는 {@link jakarta.persistence.OneToMany}의 inverse side로, 부모 테이블에
     * 컬럼을 갖지 않는다. INSERT/UPDATE 바인딩에서도 제외된다.
     */
    public boolean oneToMany() {
        return oneToMany;
    }

    public Class<?> oneToManyTargetType() {
        return oneToManyTargetType;
    }

    /**
     * {@link jakarta.persistence.OneToMany#mappedBy()}로 지정된, child entity 안의 owning property 이름.
     */
    public String oneToManyMappedBy() {
        return oneToManyMappedBy;
    }

    /**
     * {@link #manyToOne()} 또는 {@link #oneToMany()} 중 하나라도 {@code true}면 관계 property다.
     */
    public boolean isRelation() {
        return manyToOne || oneToMany;
    }

    public Object read(Object instance) {
        try {
            Object current = instance;
            for (Field hostField : embeddedHostPath) {
                current = hostField.get(current);
                if (current == null) {
                    return null;
                }
            }
            Object value = field.get(current);
            if (manyToOne && value != null) {
                // @ManyToOne property는 entity reference를 보관하지만, FK column에 바인딩되는 값은
                // 참조 대상의 @Id 값이다. binding 시점에 reflection으로 target의 @Id 필드를 찾아 그 값을 반환한다.
                return extractReferencedId(value);
            }
            return value;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read field " + field.getName(), exception);
        }
    }

    /**
     * {@code @ManyToOne} 참조 대상 인스턴스에서 {@link jakarta.persistence.Id} 필드를 찾아 그 값을 꺼낸다.
     * cycle-aware EntityMetadataFactory 없이도 동작하도록 직접 reflection으로 @Id를 탐색하며, target 클래스
     * 계층에 @Id가 없으면 {@link IllegalStateException}으로 즉시 거부한다.
     */
    private static Object extractReferencedId(Object referenced) {
        Class<?> type = referenced.getClass();
        for (Field candidate : type.getDeclaredFields()) {
            if (candidate.isAnnotationPresent(jakarta.persistence.Id.class)) {
                candidate.setAccessible(true);
                try {
                    return candidate.get(referenced);
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException(
                            "Cannot read @Id field on referenced entity " + type.getName(), exception);
                }
            }
        }
        throw new IllegalStateException(
                "@ManyToOne referenced entity " + type.getName() + " has no @Id field");
    }

    public void write(Object instance, Object value) {
        if (oneToMany) {
            // @OneToMany inverse side는 부모 테이블 컬럼이 없으므로 row 디코딩 단계에서 주입할 값도 없다.
            // 실제 child 컬렉션은 FetchGroup hydration 단계에서 별도 setter로 주입된다.
            return;
        }
        if (manyToOne) {
            // @ManyToOne row 디코딩: FK column 값(=참조 entity의 @Id)이 들어온다. entity reference 필드는
            // 사용자 entity 타입이므로 Long 등 식별자 값을 직접 set할 수 없다. 대신 target entity의 no-arg
            // 생성자로 stub 인스턴스를 만들고 @Id 필드에 FK 값을 채워 reference 자리에 둔다. FetchGroup
            // hydration이 활성화되어 있으면 이 stub은 곧 fully-loaded target으로 replace된다. hydration이
            // 비활성화된 경로(예: 명시적 fetch group 없이 관계 entity를 단독 조회)에서도 호출자는 적어도
            // id를 통해 reference identity를 식별할 수 있다.
            writeManyToOneStub(instance, value);
            return;
        }
        try {
            Object current = instance;
            for (Field hostField : embeddedHostPath) {
                Object next = hostField.get(current);
                if (next == null) {
                    next = instantiateEmbeddable(hostField.getType());
                    hostField.set(current, next);
                }
                current = next;
            }
            field.set(current, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot write field " + field.getName(), exception);
        }
    }

    /**
     * @ManyToOne row 디코딩용 stub: target entity의 no-arg 생성자로 빈 인스턴스를 만들고 @Id 필드에 FK
     * 값을 채워 entity reference 필드에 set한다. FK 값이 null이면 reference도 null로 둔다.
     */
    private void writeManyToOneStub(Object instance, Object fkValue) {
        try {
            if (fkValue == null) {
                field.set(instance, null);
                return;
            }
            Class<?> target = manyToOneTargetType != null ? manyToOneTargetType : field.getType();
            Object stub = instantiateTarget(target);
            Field idField = findIdField(target);
            idField.setAccessible(true);
            idField.set(stub, fkValue);
            field.set(instance, stub);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot write @ManyToOne reference field " + field.getName(), exception);
        }
    }

    private static Object instantiateTarget(Class<?> targetType) {
        try {
            var constructor = targetType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "@ManyToOne target type must expose a no-args constructor: " + targetType.getName(), exception);
        }
    }

    private static Field findIdField(Class<?> targetType) {
        for (Field candidate : targetType.getDeclaredFields()) {
            if (candidate.isAnnotationPresent(jakarta.persistence.Id.class)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "@ManyToOne target type " + targetType.getName() + " has no @Id field");
    }

    private static Object instantiateEmbeddable(Class<?> embeddableType) {
        try {
            var constructor = embeddableType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Embeddable type must expose a no-args constructor: " + embeddableType.getName(), exception);
        }
    }

    public Object toColumnValue(Object value) {
        if (value == null || converter == null) {
            return value;
        }
        return converter.write(value);
    }

    public Object toPropertyValue(Object value) {
        if (value == null || converter == null) {
            return value;
        }
        return converter.read(value);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PersistentProperty property)) {
            return false;
        }
        return Objects.equals(field, property.field)
                && Objects.equals(embeddedHostPath, property.embeddedHostPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, embeddedHostPath);
    }
}
