package io.nova.query.criteria;

import jakarta.persistence.criteria.ParameterExpression;

import java.util.Collection;
import java.util.Objects;

/**
 * A scalar Criteria parameter. Parameter identity, rather than its optional name, defines a binding slot.
 *
 * @param <T> parameter value type
 */
final class CriteriaParameter<T> extends AbstractCriteriaExpression<T> implements ParameterExpression<T> {

    private final Class<T> parameterType;
    private final String name;

    private CriteriaParameter(Class<T> javaType, String name) {
        super(validateType(javaType));
        this.parameterType = javaType;
        this.name = name;
    }

    static <T> CriteriaParameter<T> unnamed(Class<T> javaType) {
        return new CriteriaParameter<>(javaType, null);
    }

    static <T> CriteriaParameter<T> named(Class<T> javaType, String name) {
        if (name == null || name.isBlank()) {
            throw new CriteriaException("Criteria parameter name must not be blank");
        }
        return new CriteriaParameter<>(javaType, name);
    }

    @Override
    public Class<T> getParameterType() {
        return parameterType;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Integer getPosition() {
        return null;
    }

    private static <T> Class<T> validateType(Class<T> javaType) {
        Objects.requireNonNull(javaType, "parameter type must not be null");
        if (javaType.isPrimitive() || Collection.class.isAssignableFrom(javaType) || javaType.isArray()) {
            throw new CriteriaException("Criteria parameters support scalar reference types only: " + javaType.getName());
        }
        return javaType;
    }
}
