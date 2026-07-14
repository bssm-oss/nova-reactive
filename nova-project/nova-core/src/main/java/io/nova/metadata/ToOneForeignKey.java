package io.nova.metadata;

import java.util.List;

/**
 * 복합키({@code @EmbeddedId}/{@code @IdClass}) 엔티티를 참조하는 to-one 관계
 * ({@code @ManyToOne}/owning {@code @OneToOne})의 다중컬럼 FK 모델이다. 참조 엔티티의 각 {@code @Id}
 * 컴포넌트마다 {@link ToOneForeignKeyColumn} 1개를 <b>참조 {@code @Id} 컴포넌트 순서대로</b> 보관한다.
 *
 * <p>단일키 타겟 to-one은 이 모델을 갖지 않으며({@code null}), 기존 단일 FK 컬럼 경로를 그대로 탄다.
 * 이 모델은 복합키 타겟일 때만 생성되어 read/write/DDL/FK 네 경로가 동일한 컬럼 순서를 공유하게 한다.
 */
public final class ToOneForeignKey {
    private final List<ToOneForeignKeyColumn> columns;
    private final Class<?> targetType;

    public ToOneForeignKey(List<ToOneForeignKeyColumn> columns, Class<?> targetType) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("ToOneForeignKey requires at least one column");
        }
        this.columns = List.copyOf(columns);
        this.targetType = targetType;
    }

    /**
     * 참조 {@code @Id} 컴포넌트 순서대로 정렬된 FK 컬럼들. read/write/DDL/FK가 이 순서를 그대로 따른다.
     */
    public List<ToOneForeignKeyColumn> columns() {
        return columns;
    }

    public Class<?> targetType() {
        return targetType;
    }

    /**
     * row에서 디코드된 N개 컴포넌트 도메인 값으로 참조 엔티티 stub을 조립한다(read 경로). 모든 값이
     * {@code null}이면(참조 없음) {@code null}을 반환한다. 그 외에는 target의 no-arg 생성자로 stub을 만들고
     * 각 컴포넌트를 복합 id(@EmbeddedId holder 또는 @IdClass top-level @Id 필드들)에 채운다.
     */
    public Object assembleStub(List<Object> decodedValues) {
        if (decodedValues.size() != columns.size()) {
            throw new IllegalStateException(
                    "Composite foreign-key expected " + columns.size() + " values but got " + decodedValues.size());
        }
        boolean allNull = true;
        for (Object value : decodedValues) {
            if (value != null) {
                allNull = false;
                break;
            }
        }
        if (allNull) {
            return null;
        }
        Object stub = instantiate(targetType);
        for (int i = 0; i < columns.size(); i++) {
            columns.get(i).writeReferencedValue(stub, decodedValues.get(i));
        }
        return stub;
    }

    private static Object instantiate(Class<?> type) {
        try {
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "@ManyToOne/@OneToOne target type must expose a no-args constructor: " + type.getName(), exception);
        }
    }
}
