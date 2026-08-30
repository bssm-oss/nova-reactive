package io.nova.query.criteria;

/**
 * {@code CriteriaBuilder.count/countDistinct/sum/avg/max/min}이 만드는 집계 표현식. 피연산자는 단일
 * 컬럼 경로({@link CriteriaColumnPath})여야 하며, 스칼라 SQL 렌더 시 {@code fn("col")} 형태가 된다.
 * 집계 표현식이 SELECT/HAVING에 하나라도 등장하면 쿼리는 스칼라 실행 경로로 라우팅된다.
 *
 * @param <N> 집계 결과 타입(count=Long, avg=Double, sum/max/min=피연산자 타입)
 */
final class CriteriaAggregate<N> extends AbstractCriteriaExpression<N> {

    private final AggregateFunction function;
    private final CriteriaColumnPath operand;

    CriteriaAggregate(AggregateFunction function, CriteriaColumnPath operand, Class<N> resultType) {
        super(boxed(resultType));
        this.function = function;
        this.operand = operand;
        validateOperand();
    }

    AggregateFunction function() {
        return function;
    }

    CriteriaColumnPath operand() {
        return operand;
    }

    @Override
    public jakarta.persistence.criteria.Predicate equalTo(Object value) {
        return comparison(CompareOp.EQ, value, "equalTo");
    }

    @Override
    public jakarta.persistence.criteria.Predicate notEqualTo(Object value) {
        return comparison(CompareOp.NE, value, "notEqualTo");
    }

    private jakarta.persistence.criteria.Predicate comparison(CompareOp op, Object value, String operation) {
        validateComparison(this, value, "Expression." + operation);
        return CriteriaPredicate.comparison(this, op, value);
    }

    static void validateComparison(CriteriaAggregate<?> aggregate, Object value, String operation) {
        if (value == null) {
            throw new CriteriaException(operation + " does not accept null for an aggregate expression");
        }
        CriteriaGuards.rejectExpressionValue(value, operation);
        Class<?> type = boxed(aggregate.getJavaType());
        if (!type.isInstance(value)) {
            throw new CriteriaException(operation + " requires a " + type.getSimpleName()
                    + " value for " + aggregate.function().sqlName() + ", got "
                    + value.getClass().getSimpleName());
        }
    }

    private void validateOperand() {
        if (operand.property().isRelation()) {
            throw new CriteriaException("Aggregate operand '" + operand.property().propertyName()
                    + "' must be a scalar attribute");
        }
        if (function != AggregateFunction.COUNT && function != AggregateFunction.COUNT_DISTINCT) {
            if (operand instanceof CriteriaFrom
                    || !Number.class.isAssignableFrom(boxed(operand.property().javaType()))) {
                throw new CriteriaException(function.sqlName() + " requires a numeric scalar attribute operand");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> boxed(Class<T> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return (Class<T>) Integer.class;
        }
        if (type == long.class) {
            return (Class<T>) Long.class;
        }
        if (type == double.class) {
            return (Class<T>) Double.class;
        }
        if (type == float.class) {
            return (Class<T>) Float.class;
        }
        if (type == short.class) {
            return (Class<T>) Short.class;
        }
        if (type == byte.class) {
            return (Class<T>) Byte.class;
        }
        throw new CriteriaException("Unsupported primitive aggregate result type " + type.getName());
    }
}
