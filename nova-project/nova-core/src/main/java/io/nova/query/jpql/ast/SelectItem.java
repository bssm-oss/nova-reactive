package io.nova.query.jpql.ast;

/**
 * SELECT 절의 한 항목. {@code alias}는 {@code expr AS alias}의 별칭이며 없으면 {@code null}.
 * {@code entityAlias}가 non-null이면 이 항목은 엔티티 자체 선택(예 {@code SELECT e})을 의미하고,
 * 그 경우 {@code expression}은 {@code null}이다.
 */
public record SelectItem(Expression expression, String alias, String entityAlias) {

    public static SelectItem of(Expression expression, String alias) {
        return new SelectItem(expression, alias, null);
    }

    public static SelectItem entity(String entityAlias) {
        return new SelectItem(null, null, entityAlias);
    }

    public boolean isEntity() {
        return entityAlias != null;
    }
}
