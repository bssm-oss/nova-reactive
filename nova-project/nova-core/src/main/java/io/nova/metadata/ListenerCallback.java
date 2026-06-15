package io.nova.metadata;

import java.lang.reflect.Method;

/**
 * {@code @EntityListeners}로 등록된 외부 리스너 클래스의 콜백 1건. entity의 자체 콜백(no-arg, entity가 수신자)과
 * 달리, 리스너 콜백은 리스너 인스턴스의 메서드이며 entity를 단일 인자로 받는다 — {@code method.invoke(listener, entity)}.
 * 리스너 인스턴스는 metadata 빌드 시 1회 생성해 재사용한다(stateless 가정).
 */
public record ListenerCallback(Object listener, Method method) {
}
