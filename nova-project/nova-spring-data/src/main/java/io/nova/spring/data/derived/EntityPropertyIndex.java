package io.nova.spring.data.derived;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 단일 엔티티 클래스의 top-level property 이름 인덱스. derived query parser가 method-name token에서
 * property 이름을 greedy하게 잘라낼 때 사용한다.
 *
 * <p>매칭 대상은 {@link EntityMetadata#properties()}의 논리 property다. 따라서 field 접근 여부와 무관하게
 * 상속 및 PROPERTY access getter-only property를 포함하고, 메타데이터에서 제외된 inactive/transient
 * property는 포함하지 않는다.
 *
 * <p>매칭은 PascalCase form으로 비교한다 — method-name이 {@code findByEmailAddress}일 때 {@code EmailAddress}
 * 토큰을 lowerCamelCase property {@code emailAddress}에 매핑한다.
 */
final class EntityPropertyIndex {
    /**
     * PascalCase 길이 내림차순으로 정렬된 property 리스트. greedy 매칭에서 긴 이름을 먼저 시도하기 위함.
     */
    private final List<String> pascalDescending;
    private final List<String> camelOriginal;

    private EntityPropertyIndex(List<String> pascal, List<String> camel) {
        this.pascalDescending = pascal;
        this.camelOriginal = camel;
    }

    static EntityPropertyIndex of(EntityMetadata<?> metadata) {
        List<String> camel = metadata.properties().stream()
                .map(PersistentProperty::propertyName)
                .toList();
        List<String> pascalSorted = new ArrayList<>(camel.size());
        for (String name : camel) {
            pascalSorted.add(toPascal(name));
        }
        // 동일 length에서는 사전순 — deterministic test/error 메시지.
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < pascalSorted.size(); i++) {
            indices.add(i);
        }
        indices.sort(Comparator
                .comparingInt((Integer i) -> pascalSorted.get(i).length()).reversed()
                .thenComparing(pascalSorted::get));
        List<String> pascalOut = new ArrayList<>(indices.size());
        List<String> camelOut = new ArrayList<>(indices.size());
        for (int idx : indices) {
            pascalOut.add(pascalSorted.get(idx));
            camelOut.add(camel.get(idx));
        }
        return new EntityPropertyIndex(
                Collections.unmodifiableList(pascalOut),
                Collections.unmodifiableList(camelOut));
    }

    /**
     * 주어진 PascalCase 토큰이 등록된 property 중 하나로 시작하는지 검사한다. 매칭되면 lowerCamelCase
     * property 이름을 반환하고, 아니면 {@link Optional#empty()}.
     *
     * <p>긴 이름을 먼저 시도해 prefix 충돌(예: {@code Email}과 {@code EmailAddress}가 모두 등록된 경우
     * {@code EmailAddressLike}를 {@code EmailAddress + Like}로 잘라내도록)을 피한다.
     */
    Optional<Match> longestPrefixMatch(String token) {
        for (int i = 0; i < pascalDescending.size(); i++) {
            String pascal = pascalDescending.get(i);
            if (token.startsWith(pascal)) {
                return Optional.of(new Match(camelOriginal.get(i), token.substring(pascal.length())));
            }
        }
        return Optional.empty();
    }

    /**
     * lowerCamelCase 형태의 모든 property 이름. error 메시지에서 사용된다.
     */
    List<String> propertyNames() {
        return camelOriginal;
    }

    private static String toPascal(String camel) {
        if (camel.isEmpty()) {
            return camel;
        }
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    /**
     * @param propertyName 매칭된 lowerCamelCase property.
     * @param remainder    token에서 property 이름을 소비하고 남은 나머지 (keyword suffix 후보).
     */
    record Match(String propertyName, String remainder) {
    }
}
