package io.nova.annotation;

/**
 * {@link Enumerated} 필드에서 enum 값을 컬럼에 저장하는 방식 선택지.
 * <ul>
 *   <li>{@link #STRING} — enum constant의 {@code name()}을 {@code VARCHAR}에 저장한다.
 *       상수 재정렬에 안전하지만 컬럼 크기가 커진다.</li>
 *   <li>{@link #ORDINAL} — enum constant의 {@code ordinal()}을 {@code INTEGER}에 저장한다.
 *       크기는 작지만 enum 선언 순서를 변경하면 데이터가 깨지므로 주의가 필요하다.</li>
 * </ul>
 */
public enum EnumType {
    STRING,
    ORDINAL
}
