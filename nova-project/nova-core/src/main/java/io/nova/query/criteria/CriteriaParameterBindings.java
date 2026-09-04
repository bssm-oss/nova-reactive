package io.nova.query.criteria;

import jakarta.persistence.criteria.ParameterExpression;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable values resolved for one Criteria query subscription. This is deliberately an AST-to-value
 * resolver: it neither renders SQL nor interpolates values into SQL text.
 */
final class CriteriaParameterBindings {

    private final Map<CriteriaParameter<?>, Object> values;

    private CriteriaParameterBindings(Map<CriteriaParameter<?>, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    static CriteriaParameterBindings resolve(
            Set<? extends ParameterExpression<?>> parameters,
            Map<? extends ParameterExpression<?>, ?> identityValues,
            Map<String, ?> namedValues) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(identityValues, "identityValues must not be null");
        Objects.requireNonNull(namedValues, "namedValues must not be null");

        Set<CriteriaParameter<?>> declared = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, CriteriaParameter<?>> named = new LinkedHashMap<>();
        for (ParameterExpression<?> expression : parameters) {
            if (!(expression instanceof CriteriaParameter<?> parameter)) {
                throw new CriteriaException("Criteria query contains a parameter not created by this CriteriaBuilder");
            }
            declared.add(parameter);
            if (parameter.getPosition() != null) {
                throw new CriteriaException("Positional Criteria parameters are not supported");
            }
            if (parameter.getName() != null && named.putIfAbsent(parameter.getName(), parameter) != null) {
                throw new CriteriaException("Criteria query declares more than one parameter named '"
                        + parameter.getName() + "'");
            }
        }

        Map<CriteriaParameter<?>, Object> resolved = new IdentityHashMap<>();
        for (Map.Entry<? extends ParameterExpression<?>, ?> entry : identityValues.entrySet()) {
            if (!(entry.getKey() instanceof CriteriaParameter<?> parameter) || !declared.contains(parameter)) {
                throw new CriteriaException("Value was bound for a parameter not declared by this Criteria query");
            }
            resolved.put(parameter, validateValue(parameter, entry.getValue()));
        }
        for (Map.Entry<String, ?> entry : namedValues.entrySet()) {
            String name = entry.getKey();
            CriteriaParameter<?> parameter = named.get(name);
            if (parameter == null) {
                throw new CriteriaException("No Criteria parameter named '" + name + "' is declared by this query");
            }
            if (resolved.containsKey(parameter)) {
                throw new CriteriaException("Criteria parameter '" + name + "' was bound by both identity and name");
            }
            resolved.put(parameter, validateValue(parameter, entry.getValue()));
        }
        for (CriteriaParameter<?> parameter : declared) {
            if (!resolved.containsKey(parameter)) {
                String description = parameter.getName() == null
                        ? "unnamed parameter"
                        : "parameter '" + parameter.getName() + "'";
                throw new CriteriaException("No value bound for Criteria " + description);
            }
        }
        return new CriteriaParameterBindings(resolved);
    }

    Object resolve(CriteriaParameter<?> parameter) {
        if (!values.containsKey(parameter)) {
            throw new CriteriaException("No value bound for Criteria parameter");
        }
        return values.get(parameter);
    }

    Object resolve(Object value) {
        if (value instanceof CriteriaParameter<?> parameter) {
            return resolve(parameter);
        }
        return value;
    }

    private static Object validateValue(CriteriaParameter<?> parameter, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> || value.getClass().isArray()) {
            throw new CriteriaException("Criteria parameter values must be scalar");
        }
        if (!parameter.getJavaType().isInstance(value)) {
            throw new CriteriaException("Criteria parameter"
                    + (parameter.getName() == null ? "" : " '" + parameter.getName() + "'")
                    + " requires " + parameter.getJavaType().getSimpleName() + " but got "
                    + value.getClass().getSimpleName());
        }
        return value;
    }
}
