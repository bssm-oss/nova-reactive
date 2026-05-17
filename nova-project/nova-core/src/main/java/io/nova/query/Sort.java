package io.nova.query;

import java.util.List;

public record Sort(List<Order> orders) {
    public Sort {
        orders = List.copyOf(orders);
    }

    public static Sort by(Order... orders) {
        return new Sort(List.of(orders));
    }

    public record Order(String property, Direction direction) {
        public static Order asc(String property) {
            return new Order(property, Direction.ASC);
        }

        public static Order desc(String property) {
            return new Order(property, Direction.DESC);
        }
    }

    public enum Direction {
        ASC,
        DESC
    }
}
