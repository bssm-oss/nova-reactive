package io.nova.query.criteria;

import java.math.BigDecimal;
import java.math.BigInteger;

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
    private final boolean comparisonUsesOperandConverter;

    CriteriaAggregate(AggregateFunction function, CriteriaColumnPath operand, Class<N> resultType) {
        this(function, operand, resultType, function == AggregateFunction.SUM
                || function == AggregateFunction.MIN || function == AggregateFunction.MAX);
    }

    CriteriaAggregate(
            AggregateFunction function,
            CriteriaColumnPath operand,
            Class<N> resultType,
            boolean comparisonUsesOperandConverter) {
        super(boxed(resultType));
        this.function = function;
        this.operand = operand;
        this.comparisonUsesOperandConverter = comparisonUsesOperandConverter;
        validateOperand();
    }

    AggregateFunction function() {
        return function;
    }

    CriteriaColumnPath operand() {
        return operand;
    }

    /**
     * Converts an aggregate comparison value only when the SQL aggregate preserves the operand's
     * domain representation. COUNT, AVG, and widened SUM produce independent numeric result domains.
     */
    Object toComparisonColumnValue(Object value) {
        return comparisonUsesOperandConverter ? operand.property().toColumnValue(value) : value;
    }

    Object normalizeResult(Object value) {
        if (value == null) {
            return null;
        }
        Class<?> resultType = boxed(getJavaType());
        if (resultType.isInstance(value)) {
            return value;
        }
        if (!(value instanceof Number number)) {
            throw resultTypeMismatch(value, resultType);
        }
        try {
            return normalizeNumber(number, resultType);
        } catch (ArithmeticException | NumberFormatException e) {
            throw resultTypeMismatch(value, resultType, e);
        }
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

    private CriteriaException resultTypeMismatch(Object value, Class<?> resultType) {
        return resultTypeMismatch(value, resultType, null);
    }

    private CriteriaException resultTypeMismatch(Object value, Class<?> resultType, Exception cause) {
        String message = function.sqlName() + " returned " + value.getClass().getSimpleName()
                + " which cannot be represented exactly as declared " + resultType.getSimpleName();
        return cause == null ? new CriteriaException(message) : new CriteriaException(message, cause);
    }

    private static Object normalizeNumber(Number value, Class<?> target) {
        if (target == BigDecimal.class) {
            return decimal(value);
        }
        if (target == BigInteger.class) {
            return decimal(value).toBigIntegerExact();
        }
        if (target == Byte.class) {
            return decimal(value).byteValueExact();
        }
        if (target == Short.class) {
            return decimal(value).shortValueExact();
        }
        if (target == Integer.class) {
            return decimal(value).intValueExact();
        }
        if (target == Long.class) {
            return decimal(value).longValueExact();
        }
        if (target == Float.class) {
            BigDecimal original = canonicalDecimal(value);
            float converted = value.floatValue();
            if (!Float.isFinite(converted)
                    || original.compareTo(new BigDecimal((double) converted)) != 0) {
                throw new ArithmeticException("outside Float range");
            }
            return converted;
        }
        if (target == Double.class) {
            BigDecimal original = canonicalDecimal(value);
            double converted = value.doubleValue();
            if (!Double.isFinite(converted) || original.compareTo(new BigDecimal(converted)) != 0) {
                throw new ArithmeticException("outside Double range");
            }
            return converted;
        }
        throw new CriteriaException("Unsupported aggregate result type " + target.getName());
    }

    private static BigDecimal decimal(Number value) {
        return canonicalDecimal(value);
    }

    private static BigDecimal canonicalDecimal(Number value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(value.longValue());
        }
        if ((value instanceof Double d && !Double.isFinite(d)) || (value instanceof Float f && !Float.isFinite(f))) {
            throw new ArithmeticException("non-finite value");
        }
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d);
        }
        if (value instanceof Float f) {
            return BigDecimal.valueOf((double) f);
        }
        return new BigDecimal(value.toString());
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
