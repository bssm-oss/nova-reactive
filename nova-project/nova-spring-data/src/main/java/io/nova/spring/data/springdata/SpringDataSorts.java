package io.nova.spring.data.springdata;

import io.nova.query.Sort;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Data Commons {@code org.springframework.data.domain.Sort}와 Nova 자체 {@link Sort}
 * 사이의 양방향 브릿지다.
 *
 * <p>이 클래스(및 같은 패키지의 {@link SpringDataPageables})는 {@code spring-data-commons}에
 * 대해 <b>optional</b>(compileOnly)로 컴파일된다. Nova 사용자 중 Spring Data 표준 타입을
 * 쓰지 않는 쪽은 이 클래스를 로드하지 않으므로 {@code spring-data-commons}를 클래스패스에 둘
 * 필요가 없다. 표준 {@code Pageable}/{@code Sort} 오버로드를 실제로 호출하는 사용자만
 * {@code spring-data-commons}를 런타임 의존으로 추가하면 된다.
 *
 * <h2>지원 및 근사 범위</h2>
 * <ul>
 *   <li>{@code property} + {@code ASC}/{@code DESC} 방향은 1:1 매핑된다.</li>
 *   <li>Spring {@code Sort.unsorted()}는 Nova의 "정렬 없음"을 뜻하는 {@code null}로 매핑된다.</li>
 *   <li>Nova {@link Sort}에는 대소문자 무시(ignore-case)/null-handling 개념이 없다. 따라서
 *       Spring {@code Order.isIgnoreCase()}가 {@code true}이거나 {@code getNullHandling()}이
 *       {@code NATIVE}가 아니면 조용히 무시하지 않고 {@link IllegalArgumentException}으로
 *       <b>fail-fast</b>한다.</li>
 * </ul>
 */
public final class SpringDataSorts {

    private SpringDataSorts() {
    }

    /**
     * Spring Data {@code Sort}를 Nova {@link Sort}로 변환한다.
     *
     * @param springSort Spring Data 정렬 사양. {@code null}이거나 {@code unsorted()}이면
     *                   Nova의 "정렬 없음"을 뜻하는 {@code null}을 반환한다.
     * @return Nova {@link Sort}, 정렬이 없으면 {@code null}
     * @throws IllegalArgumentException ignore-case 또는 비-NATIVE null-handling처럼 Nova가
     *                                  표현할 수 없는 정렬 옵션이 지정된 경우
     */
    public static Sort toNova(org.springframework.data.domain.Sort springSort) {
        if (springSort == null || springSort.isUnsorted()) {
            return null;
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (org.springframework.data.domain.Sort.Order order : springSort) {
            if (order.isIgnoreCase()) {
                throw new IllegalArgumentException(
                        "Nova Sort does not support ignore-case ordering (property '"
                                + order.getProperty() + "'). Remove ignoreCase() from the Spring Sort.Order.");
            }
            if (order.getNullHandling() != org.springframework.data.domain.Sort.NullHandling.NATIVE) {
                throw new IllegalArgumentException(
                        "Nova Sort does not support explicit null-handling ("
                                + order.getNullHandling() + ") for property '" + order.getProperty()
                                + "'. Only NATIVE null-handling is supported.");
            }
            orders.add(order.isAscending()
                    ? Sort.Order.asc(order.getProperty())
                    : Sort.Order.desc(order.getProperty()));
        }
        return Sort.by(orders.toArray(new Sort.Order[0]));
    }

    /**
     * Nova {@link Sort}를 Spring Data {@code Sort}로 변환한다.
     *
     * @param novaSort Nova 정렬 사양. {@code null}이거나 order가 비어 있으면 Spring
     *                 {@code Sort.unsorted()}를 반환한다.
     * @return Spring Data {@code Sort}
     */
    public static org.springframework.data.domain.Sort toSpring(Sort novaSort) {
        if (novaSort == null || novaSort.orders().isEmpty()) {
            return org.springframework.data.domain.Sort.unsorted();
        }
        List<org.springframework.data.domain.Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : novaSort.orders()) {
            org.springframework.data.domain.Sort.Direction direction =
                    order.direction() == Sort.Direction.ASC
                            ? org.springframework.data.domain.Sort.Direction.ASC
                            : org.springframework.data.domain.Sort.Direction.DESC;
            orders.add(new org.springframework.data.domain.Sort.Order(direction, order.property()));
        }
        return org.springframework.data.domain.Sort.by(orders);
    }
}
