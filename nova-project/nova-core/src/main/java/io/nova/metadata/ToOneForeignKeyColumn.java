package io.nova.metadata;

import io.nova.convert.AttributeConverter;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 복합키({@code @EmbeddedId}/{@code @IdClass}) 엔티티를 참조하는 to-one 관계
 * ({@code @ManyToOne}/owning {@code @OneToOne})에서, 참조 엔티티의 {@code @Id} 컴포넌트 하나에 대응하는
 * 자식 테이블 FK 컬럼 1개의 매핑이다.
 *
 * <p>read(row→stub 조립)·write(참조 엔티티→FK 바인딩)·DDL·FK 제약 네 경로가 <b>동일한 컴포넌트 순서</b>를
 * 공유하도록, {@link EntityMetadataFactory}가 참조 엔티티의 {@code @Id} 컴포넌트 순서대로 이 컬럼들을 만든다
 * (컴포넌트 순서 어긋남은 silent 손상이므로 단일 자리에서 결정한다).
 */
public final class ToOneForeignKeyColumn {
    private final String columnName;
    private final String referencedColumnName;
    private final Class<?> columnType;
    private final int length;
    private final boolean nullable;
    private final AttributeConverter<Object, Object> converter;
    /**
     * 참조 엔티티 인스턴스에서 이 컴포넌트의 도메인 값(leaf)까지 도달하는 outer→inner 필드 체인.
     * {@code @IdClass}는 top-level {@code @Id} 필드 1개, {@code @EmbeddedId}는
     * {@code [holder 필드, embeddable leaf 필드]} 2개다.
     */
    private final List<Field> referencedPath;

    public ToOneForeignKeyColumn(
            String columnName,
            String referencedColumnName,
            Class<?> columnType,
            int length,
            boolean nullable,
            AttributeConverter<Object, Object> converter,
            List<Field> referencedPath) {
        this.columnName = columnName;
        this.referencedColumnName = referencedColumnName;
        this.columnType = columnType;
        this.length = length;
        this.nullable = nullable;
        this.converter = converter;
        this.referencedPath = List.copyOf(referencedPath);
        for (Field field : this.referencedPath) {
            field.setAccessible(true);
        }
    }

    public String columnName() {
        return columnName;
    }

    public String referencedColumnName() {
        return referencedColumnName;
    }

    /**
     * 이 FK 컬럼의 저장 표현 타입. row 디코딩({@code row.get})과 schema 컬럼 SQL 타입 유도에 함께 쓰인다
     * — 참조 {@code @Id} 컴포넌트의 도메인 타입이 아니라 {@code @Convert}/{@code @Enumerated}/UUID 등이 반영된
     * 저장타입이다(read-source-type 함정 회피).
     */
    public Class<?> columnType() {
        return columnType;
    }

    public int length() {
        return length;
    }

    public boolean nullable() {
        return nullable;
    }

    /**
     * 도메인 값(참조 {@code @Id} 컴포넌트 타입)을 저장 표현으로 인코딩한다. converter가 없으면 그대로 반환.
     */
    public Object toColumnValue(Object domainValue) {
        if (domainValue == null || converter == null) {
            return domainValue;
        }
        return converter.write(domainValue);
    }

    /**
     * row에서 읽은 저장 표현 값을 도메인 값으로 복원한다. converter가 없으면 그대로 반환.
     */
    public Object toPropertyValue(Object storedValue) {
        if (storedValue == null || converter == null) {
            return storedValue;
        }
        return converter.read(storedValue);
    }

    /**
     * 참조 엔티티 인스턴스에서 이 컴포넌트의 도메인 값을 꺼낸다(write 경로). 체인 중간이 {@code null}이면
     * (예: {@code @EmbeddedId} holder 미설정) {@code null}을 반환한다.
     */
    public Object readReferencedValue(Object referenced) {
        try {
            Object current = referenced;
            for (Field field : referencedPath) {
                current = field.get(current);
                if (current == null) {
                    return null;
                }
            }
            return current;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot read composite foreign-key component for column " + columnName, exception);
        }
    }

    /**
     * 조립 중인 stub 인스턴스에 이 컴포넌트의 도메인 값을 써넣는다(read 경로). 필요 시 중간 holder
     * ({@code @EmbeddedId}의 {@code @Embeddable})를 no-arg 생성자로 lazy 생성한다.
     */
    public void writeReferencedValue(Object stub, Object domainValue) {
        try {
            Object current = stub;
            for (int i = 0; i < referencedPath.size() - 1; i++) {
                Field hostField = referencedPath.get(i);
                Object next = hostField.get(current);
                if (next == null) {
                    next = instantiate(hostField.getType());
                    hostField.set(current, next);
                }
                current = next;
            }
            referencedPath.get(referencedPath.size() - 1).set(current, domainValue);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot assemble composite foreign-key component for column " + columnName, exception);
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Composite-key holder type must expose a no-args constructor: " + type.getName(), exception);
        }
    }
}
