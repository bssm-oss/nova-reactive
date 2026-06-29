package io.nova.metadata;

import jakarta.persistence.EnumType;
import jakarta.persistence.GenerationType;
import io.nova.convert.AttributeConverter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

public final class PersistentProperty {
    private final Field field;
    /**
     * leaf 필드의 {@link VarHandle} — reflective {@code Field.get/set}보다 빠른 접근 경로다. 모듈 제약 등으로
     * 생성에 실패하면 {@code null}로 두고 {@link #field} 리플렉션으로 fallback한다(동작 동일). 기본(non-exact)
     * VarHandle 접근이라 primitive 필드의 boxing/unboxing은 자동 처리된다.
     */
    private final VarHandle fieldHandle;
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
    private final boolean lob;
    /**
     * {@code @Convert} 변환기가 적용된 property의 저장 표현 타입(Y). row 디코딩과 schema 컬럼 타입이
     * 도메인 타입(javaType=X)이 아니라 이 저장 타입을 따르게 한다. 변환기가 없으면 {@code null}.
     */
    private final Class<?> converterColumnType;
    /**
     * inverse-side {@code @OneToOne}({@code mappedBy} 지정) 마커. 이 테이블에는 컬럼이 없고(소유 측이 FK를
     * 가짐) hydration 단계에서 단건 child로 주입된다. owning-side {@code @OneToOne}은 별도 플래그 없이
     * {@code @ManyToOne}과 동일하게({@link #manyToOne}) 모델링된다.
     */
    private final boolean inverseToOne;
    /**
     * {@code @ManyToMany} link table 매핑 메타데이터. owning/inverse 양측 모두 채워지며, 이 property는
     * 부모 테이블에 컬럼이 없는 marker다(컬렉션은 hydration 단계에서 주입, link row는 save 시 동기화). M2M가
     * 아니면 {@code null}.
     */
    private final ManyToManyInfo manyToManyInfo;
    /**
     * {@code @ElementCollection} 값 컬렉션의 collection table 매핑. 이 property는 부모 테이블에 컬럼이 없는
     * marker이며, 값들은 별도 테이블에 저장되고 hydration 단계에서 주입된다. 값 컬렉션이 아니면 {@code null}.
     */
    private final ElementCollectionInfo elementCollectionInfo;
    /**
     * {@code @OneToMany}의 cascade / orphanRemoval 메타데이터. cascade도 orphanRemoval도 없는 marker-only
     * {@code @OneToMany}와 다른 모든 property는 {@code null}이다. {@link io.nova.core.SimpleReactiveEntityOperations}의
     * save/delete/flush 경로가 이 값을 보고 child 전파 여부를 결정한다.
     */
    private final OneToManyInfo oneToManyInfo;
    /**
     * {@code @GeneratedValue(TABLE)} + {@code @TableGenerator} 매핑 메타데이터. {@code @Id} property에만
     * 설정되며, 그 외에는 {@code null}이다. generator 테이블 DDL/seed와 다음 값 취득에서 사용된다.
     */
    private final TableGeneratorInfo tableGeneratorInfo;
    /**
     * {@code @MapsId} 파생 식별자(shared primary key) 마커. owning {@code @OneToOne}/{@code @ManyToOne}
     * 관계 property에만 {@code true}로 설정되며, 이 관계가 가리키는 연관 엔티티의 PK가 호스트 엔티티의
     * {@code @Id}로 파생됨을 나타낸다. {@link io.nova.core.SimpleReactiveEntityOperations}의 save 경로가
     * 이 마커를 보고 INSERT 전 연관 엔티티 PK를 owner의 {@code @Id}에 복사하고, app-assigned 파생키와
     * id-null isNew 휴리스틱이 충돌하지 않도록 존재확인 SELECT로 insert/update를 가른다. {@code @MapsId}가
     * 아니면 {@code false}.
     */
    private final boolean mapsId;
    /**
     * {@code @MapsId("attr")}로 지정된, 복합키 안에서 파생될 컴포넌트 속성 이름. 단순(전체 키 파생)
     * {@code @MapsId}는 빈 문자열이다. v1은 단일 {@code @Id} 전체 파생만 지원하므로 비어있지 않은 값은
     * {@link EntityMetadataFactory}가 fail-fast로 거부한다(예약 메타데이터).
     */
    private final String mapsIdValue;
    /**
     * {@code @Access(AccessType.PROPERTY)} 마커. {@code true}이면 이 property의 상태를 leaf field가 아니라
     * JavaBean getter/setter({@link #propertyAccessGetter}/{@link #propertyAccessSetter})로 read/write한다.
     * {@code false}(기본, FIELD access)이면 field 접근({@link #fieldHandle}/{@link #field})을 쓴다. 호출당
     * reflection 추론 없이 분기하도록 생성자 시점에 access 전략을 확정해 둔다.
     */
    private final boolean propertyAccess;
    /**
     * PROPERTY access일 때 상태를 읽는 JavaBean getter({@code getX}/{@code isX}). {@link #propertyAccess}가
     * {@code true}일 때만 non-null이며, embedded host-path를 따라 도달한 leaf holder 인스턴스에서 호출된다.
     */
    private final Method propertyAccessGetter;
    /**
     * PROPERTY access일 때 상태를 쓰는 JavaBean setter({@code setX}). {@link #propertyAccess}가 {@code true}일
     * 때만 non-null이며, embedded host-path를 따라 도달한 leaf holder 인스턴스에서 호출된다.
     */
    private final Method propertyAccessSetter;
    /**
     * {@code @ManyToOne} / {@code @OneToOne}의 cascade 메타데이터. cascade가 지정되지 않은 marker-only 단건 관계와
     * 그 외 모든 property는 {@code null}이다. {@link io.nova.core.SimpleReactiveEntityOperations}의 save/delete
     * 경로가 이 값을 보고 참조 엔티티 전파(선저장 후 FK 세팅 / 삭제 전파) 여부를 결정한다.
     */
    private final ToOneCascadeInfo toOneCascadeInfo;

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
            String columnDefinition,
            boolean lob,
            Class<?> converterColumnType,
            boolean inverseToOne,
            ManyToManyInfo manyToManyInfo,
            ElementCollectionInfo elementCollectionInfo,
            OneToManyInfo oneToManyInfo,
            TableGeneratorInfo tableGeneratorInfo,
            boolean mapsId,
            String mapsIdValue,
            boolean propertyAccess,
            Method propertyAccessGetter,
            Method propertyAccessSetter,
            ToOneCascadeInfo toOneCascadeInfo
    ) {
        this.field = field;
        this.field.setAccessible(true);
        this.fieldHandle = resolveFieldHandle(field);
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
        this.lob = lob;
        this.converterColumnType = converterColumnType;
        this.inverseToOne = inverseToOne;
        this.manyToManyInfo = manyToManyInfo;
        this.elementCollectionInfo = elementCollectionInfo;
        this.oneToManyInfo = oneToManyInfo;
        this.tableGeneratorInfo = tableGeneratorInfo;
        this.mapsId = mapsId;
        this.mapsIdValue = mapsIdValue == null ? "" : mapsIdValue;
        this.propertyAccess = propertyAccess;
        this.propertyAccessGetter = propertyAccessGetter;
        this.propertyAccessSetter = propertyAccessSetter;
        if (propertyAccess) {
            if (propertyAccessGetter == null || propertyAccessSetter == null) {
                throw new IllegalStateException(
                        "PROPERTY access property " + propertyName
                                + " requires both a getter and a setter");
            }
            propertyAccessGetter.setAccessible(true);
            propertyAccessSetter.setAccessible(true);
        }
        this.toOneCascadeInfo = toOneCascadeInfo;
    }

    /**
     * 이 property의 nullable 여부만 바꾼 복사본을 만든다. SINGLE_TABLE 상속에서 서브타입 전용 컬럼은
     * 다른 서브타입 row에서는 비어 있어야 하므로, 단일 테이블 union DDL을 만들 때 강제로 nullable로
     * 낮추는 용도로 사용한다. 나머지 모든 메타데이터(field/converter/embedded path 등)는 그대로 보존한다.
     */
    public PersistentProperty withNullable(boolean newNullable) {
        if (this.nullable == newNullable) {
            return this;
        }
        return new PersistentProperty(
                field,
                propertyName,
                columnName,
                javaType,
                id,
                version,
                newNullable,
                length,
                precision,
                scale,
                generationType,
                generator,
                converter,
                createdAt,
                updatedAt,
                softDelete,
                embedded,
                embeddedHostPath,
                enumerated,
                enumType,
                json,
                manyToOne,
                manyToOneTargetType,
                manyToOneNullable,
                oneToMany,
                oneToManyTargetType,
                oneToManyMappedBy,
                insertable,
                updatable,
                unique,
                columnDefinition,
                lob,
                converterColumnType,
                inverseToOne,
                manyToManyInfo,
                elementCollectionInfo,
                oneToManyInfo,
                tableGeneratorInfo,
                mapsId,
                mapsIdValue,
                propertyAccess,
                propertyAccessGetter,
                propertyAccessSetter,
                toOneCascadeInfo
        );
    }

    /**
     * 이 property의 {@code id} 플래그만 {@code true}로 올린 복사본을 만든다. {@code @EmbeddedId}로 펼쳐진
     * 복합키 컴포넌트는 {@code @Embeddable} 안에 {@code @Id}가 없는 평범한 필드로 선언되므로
     * {@link EntityMetadataFactory}가 컬럼으로 펼친 뒤 이 메서드로 id 컴포넌트임을 표시한다. 나머지 모든
     * 메타데이터(embedded host path, converter 등)는 그대로 보존한다.
     */
    public PersistentProperty withId() {
        if (this.id) {
            return this;
        }
        return new PersistentProperty(
                field,
                propertyName,
                columnName,
                javaType,
                true,
                version,
                nullable,
                length,
                precision,
                scale,
                generationType,
                generator,
                converter,
                createdAt,
                updatedAt,
                softDelete,
                embedded,
                embeddedHostPath,
                enumerated,
                enumType,
                json,
                manyToOne,
                manyToOneTargetType,
                manyToOneNullable,
                oneToMany,
                oneToManyTargetType,
                oneToManyMappedBy,
                insertable,
                updatable,
                unique,
                columnDefinition,
                lob,
                converterColumnType,
                inverseToOne,
                manyToManyInfo,
                elementCollectionInfo,
                oneToManyInfo,
                tableGeneratorInfo,
                mapsId,
                mapsIdValue,
                propertyAccess,
                propertyAccessGetter,
                propertyAccessSetter,
                toOneCascadeInfo
        );
    }

    /**
     * {@code @Lob} 컬럼 여부. {@code true}이면 schema 생성 시 dialect의 LOB 타입(CLOB/BLOB류)을 쓴다.
     */
    public boolean lob() {
        return lob;
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
        if (converterColumnType != null) {
            return converterColumnType;
        }
        return javaType;
    }

    /**
     * {@code @Convert} 변환기가 적용된 경우 그 저장 표현 타입(Y), 아니면 {@code null}.
     * {@link io.nova.sql.AbstractSchemaGenerator}가 컬럼 SQL 타입을 도메인 타입이 아닌 저장 타입으로
     * 유도할 때 사용한다.
     */
    public Class<?> converterColumnType() {
        return converterColumnType;
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
     * {@code @OneToMany}의 cascade / orphanRemoval 메타데이터. cascade도 orphanRemoval도 지정되지 않은
     * marker-only {@code @OneToMany}와 관계가 아닌 property는 {@code null}을 반환한다.
     */
    public OneToManyInfo oneToManyInfo() {
        return oneToManyInfo;
    }

    /**
     * 이 {@code @OneToMany}가 parent save() 시 child를 자동 저장(cascade PERSIST/ALL)해야 하는지.
     */
    public boolean cascadePersistChildren() {
        return oneToManyInfo != null && (oneToManyInfo.cascadePersist() || oneToManyInfo.cascadeMerge());
    }

    /**
     * 이 {@code @OneToMany}가 parent delete() 시 child를 자동 삭제(cascade REMOVE/ALL)해야 하는지.
     */
    public boolean cascadeRemoveChildren() {
        return oneToManyInfo != null && oneToManyInfo.cascadeRemove();
    }

    /**
     * 이 {@code @OneToMany}가 orphanRemoval=true인지. flush 시 스냅샷 대비 컬렉션에서 빠진 child를 삭제한다.
     */
    public boolean orphanRemoval() {
        return oneToManyInfo != null && oneToManyInfo.orphanRemoval();
    }

    /**
     * {@code @ManyToOne} / {@code @OneToOne}의 cascade 메타데이터. cascade가 지정되지 않은 marker-only 단건 관계와
     * 관계가 아닌 property는 {@code null}을 반환한다.
     */
    public ToOneCascadeInfo toOneCascadeInfo() {
        return toOneCascadeInfo;
    }

    /**
     * 이 to-one 관계({@code @ManyToOne}/{@code @OneToOne})가 owner save() 시 참조 엔티티를 먼저 저장
     * (cascade PERSIST/MERGE/ALL)해 generated id를 확보한 뒤 FK로 연결해야 하는지.
     */
    public boolean cascadePersistReference() {
        return toOneCascadeInfo != null && (toOneCascadeInfo.cascadePersist() || toOneCascadeInfo.cascadeMerge());
    }

    /**
     * 이 to-one 관계가 {@code MERGE}(또는 {@code ALL}) cascade를 가지는지. JPA에서 PERSIST는 새(transient)
     * 참조에만 전파되지만 MERGE는 이미 영속된 참조에도 상태를 전파하므로, 이미 id가 있는 참조를 재저장할지
     * 가르는 데 쓴다.
     */
    public boolean cascadeMergeReference() {
        return toOneCascadeInfo != null && toOneCascadeInfo.cascadeMerge();
    }

    /**
     * 이 to-one 관계가 owner delete() 시 참조 엔티티를 자동 삭제(cascade REMOVE/ALL)해야 하는지.
     */
    public boolean cascadeRemoveReference() {
        return toOneCascadeInfo != null && toOneCascadeInfo.cascadeRemove();
    }

    /**
     * {@link #manyToOne()} 또는 {@link #oneToMany()} 중 하나라도 {@code true}면 관계 property다.
     */
    public boolean isRelation() {
        return manyToOne || oneToMany || inverseToOne || manyToMany();
    }

    /**
     * {@code true}이면 이 property는 {@code @ManyToMany}이며 {@link #manyToManyInfo()}가 link table 매핑을
     * 담는다. owning/inverse 모두 부모 테이블에 컬럼이 없는 marker다.
     */
    public boolean manyToMany() {
        return manyToManyInfo != null;
    }

    public ManyToManyInfo manyToManyInfo() {
        return manyToManyInfo;
    }

    /**
     * {@code true}이면 이 property는 {@code @ElementCollection} 값 컬렉션이며 {@link #elementCollectionInfo()}가
     * collection table 매핑을 담는다. 부모 테이블에 컬럼이 없는 marker다.
     */
    public boolean elementCollection() {
        return elementCollectionInfo != null;
    }

    public ElementCollectionInfo elementCollectionInfo() {
        return elementCollectionInfo;
    }

    /**
     * {@code true}이면 이 property는 {@code @GeneratedValue(TABLE)} + {@code @TableGenerator}로 식별자를
     * 발급받으며 {@link #tableGeneratorInfo()}가 generator 테이블 매핑을 담는다. {@code @Id}에만 설정된다.
     */
    public boolean tableGenerated() {
        return tableGeneratorInfo != null;
    }

    public TableGeneratorInfo tableGeneratorInfo() {
        return tableGeneratorInfo;
    }

    /**
     * {@code true}이면 이 owning to-one 관계 property는 {@code @MapsId}로 마킹되어 호스트 엔티티의
     * {@code @Id}가 연관 엔티티의 PK로부터 파생된다(shared primary key). save 경로가 INSERT 전 연관
     * 엔티티 PK를 owner의 {@code @Id}에 복사한다.
     */
    public boolean mapsId() {
        return mapsId;
    }

    /**
     * {@code @MapsId("attr")}로 지정된, 복합키 안에서 파생될 컴포넌트 속성 이름. 단순(전체 키 파생)
     * {@code @MapsId}는 빈 문자열을 반환한다.
     */
    public String mapsIdValue() {
        return mapsIdValue;
    }

    /**
     * {@code true}이면 이 property는 {@code @Access(AccessType.PROPERTY)}로 매핑되어 상태를 JavaBean
     * getter/setter로 read/write한다. {@code false}(기본)이면 field 접근을 쓴다.
     */
    public boolean propertyAccess() {
        return propertyAccess;
    }

    /**
     * PROPERTY access getter. {@link #propertyAccess()}가 {@code false}이면 {@code null}.
     */
    public Method propertyAccessGetter() {
        return propertyAccessGetter;
    }

    /**
     * PROPERTY access setter. {@link #propertyAccess()}가 {@code false}이면 {@code null}.
     */
    public Method propertyAccessSetter() {
        return propertyAccessSetter;
    }

    /**
     * inverse-side {@code @OneToOne}({@code mappedBy})이면 {@code true}. 이 테이블에 컬럼이 없는 마커이며
     * hydration에서 단건 child가 주입된다.
     */
    public boolean inverseToOne() {
        return inverseToOne;
    }

    /**
     * leaf 필드의 read/write VarHandle을 만든다. {@code private} 패키지 lookup으로 unreflect하며, 모듈
     * 미개방 등으로 실패하면 {@code null}을 반환해 reflective {@link Field} 경로로 fallback한다. {@code final}
     * 필드는 VarHandle set이 불가하므로(리플렉션도 동일) 건너뛰어 동작을 일관되게 유지한다.
     */
    private static VarHandle resolveFieldHandle(Field field) {
        if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup());
            return lookup.unreflectVarHandle(field);
        } catch (Throwable ignored) {
            return null;
        }
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
            // @Access(PROPERTY)이면 leaf field 대신 JavaBean getter로 상태를 읽는다(access 전략은 생성자에서 확정).
            Object value = propertyAccess
                    ? invokeGetter(current)
                    : (fieldHandle != null ? fieldHandle.get(current) : field.get(current));
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
     * PROPERTY access getter를 holder 인스턴스에 대해 호출한다. 호출 대상 메서드가 던진 예외는
     * {@link IllegalStateException}으로 감싸 전파한다.
     */
    private Object invokeGetter(Object holder) {
        try {
            return propertyAccessGetter.invoke(holder);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot read PROPERTY-access property " + propertyName
                            + " via " + propertyAccessGetter.getName(), exception);
        }
    }

    /**
     * PROPERTY access setter를 holder 인스턴스에 대해 호출한다. 호출 대상 메서드가 던진 예외는
     * {@link IllegalStateException}으로 감싸 전파한다.
     */
    private void invokeSetter(Object holder, Object value) {
        try {
            propertyAccessSetter.invoke(holder, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot write PROPERTY-access property " + propertyName
                            + " via " + propertyAccessSetter.getName(), exception);
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

    /**
     * findById/deleteById 등에 전달된 <em>id 값 객체</em>에서 이 컬럼에 바인딩할 값을 꺼낸다. 단일 키
     * (embedded host path 없음)는 id 객체 자체가 곧 컬럼 값이므로 그대로 반환한다. {@code @EmbeddedId}
     * 복합키 컴포넌트는 id 객체가 {@code @Embeddable} 인스턴스이므로 leaf field를 직접 읽는다 — entity가
     * 아니라 id holder에서 읽으므로 {@link #read(Object)}의 embedded host-path traversal을 거치지 않는다.
     */
    public Object readFromIdHolder(Object idHolder) {
        if (embeddedHostPath.isEmpty()) {
            return idHolder;
        }
        if (propertyAccess) {
            return invokeGetter(idHolder);
        }
        try {
            return fieldHandle != null ? fieldHandle.get(idHolder) : field.get(idHolder);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read @EmbeddedId component " + field.getName(), exception);
        }
    }

    /**
     * entity 인스턴스에서 이 property가 속한 가장 안쪽 embedded host(예: {@code @EmbeddedId} holder 객체)를
     * 반환한다. embedded가 아니면 entity 자신을 그대로 돌려준다. host 체인 중간이 {@code null}이면 {@code null}.
     * 복합키 entity에서 id 값 객체(holder)를 통째로 꺼낼 때 사용한다.
     */
    public Object readHostHolder(Object instance) {
        try {
            Object current = instance;
            for (Field hostField : embeddedHostPath) {
                current = hostField.get(current);
                if (current == null) {
                    return null;
                }
            }
            return current;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read embedded host for " + field.getName(), exception);
        }
    }

    public void write(Object instance, Object value) {
        if (oneToMany || inverseToOne || manyToMany() || elementCollection()) {
            // @OneToMany / inverse @OneToOne / @ManyToMany / @ElementCollection은 부모 테이블 컬럼이 없으므로
            // row 디코딩 단계에서 주입할 값이 없다. 실제 값은 hydration 단계에서 별도 setter로 주입된다.
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
            // @Access(PROPERTY)이면 leaf field 대신 JavaBean setter로 상태를 쓴다(access 전략은 생성자에서 확정).
            if (propertyAccess) {
                invokeSetter(current, value);
            } else if (fieldHandle != null) {
                fieldHandle.set(current, value);
            } else {
                field.set(current, value);
            }
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
