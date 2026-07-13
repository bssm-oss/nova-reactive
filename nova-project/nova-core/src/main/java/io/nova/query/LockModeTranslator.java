package io.nova.query;

import jakarta.persistence.LockModeType;

import java.util.Objects;

/**
 * JPA {@link LockModeType}를 Nova의 잠금/버전 의미로 변환하는 매핑 계층이다. Nova 자체 잠금 모델은
 * {@link LockMode}(NONE/FOR_UPDATE/FOR_SHARE)와 {@code @Version} 낙관락으로 나뉘므로, JPA의 8개
 * {@code LockModeType} 값을 이 두 축(pessimistic {@link LockMode} + 버전 검증/강제 증분)으로 해석한다.
 *
 * <p><b>매핑 표.</b>
 * <ul>
 *   <li>{@code NONE} → ({@link LockMode#NONE}, versionCheck=false, forceIncrement=false)</li>
 *   <li>{@code READ}, {@code OPTIMISTIC} → ({@link LockMode#NONE}, versionCheck=true, forceIncrement=false)</li>
 *   <li>{@code WRITE}, {@code OPTIMISTIC_FORCE_INCREMENT} → ({@link LockMode#NONE}, versionCheck=true, forceIncrement=true)</li>
 *   <li>{@code PESSIMISTIC_READ} → ({@link LockMode#FOR_SHARE}, false, false)</li>
 *   <li>{@code PESSIMISTIC_WRITE} → ({@link LockMode#FOR_UPDATE}, false, false)</li>
 *   <li>{@code PESSIMISTIC_FORCE_INCREMENT} → ({@link LockMode#FOR_UPDATE}, versionCheck=true, forceIncrement=true)</li>
 * </ul>
 * ({@code READ}/{@code WRITE}는 각각 {@code OPTIMISTIC}/{@code OPTIMISTIC_FORCE_INCREMENT}의 deprecated 별칭이다.)
 *
 * <p><b>표현 불가 조합 fail-fast.</b> 순수 타입 매핑은 8개 값 모두 총함수로 표현되지만, 버전 의미가 필요한
 * 모드({@link #requiresVersion(LockModeType)}=true)를 {@code @Version}이 없는 엔티티에 요청하면 표현할 수
 * 없다. 이 검증은 엔티티 메타데이터가 필요하므로 호출부(EntityManager)가 {@link #requiresVersion(LockModeType)}로
 * 판정해 {@link IllegalArgumentException}으로 거부한다.
 */
public final class LockModeTranslator {

    private LockModeTranslator() {
    }

    /**
     * {@link LockModeType} 하나를 (SELECT에 적용할 pessimistic {@link LockMode}, 버전 검증 여부,
     * 버전 강제 증분 여부)로 해석한 결과.
     */
    public record ResolvedLock(LockMode lockMode, boolean versionCheck, boolean forceIncrement) {
        public ResolvedLock {
            Objects.requireNonNull(lockMode, "lockMode must not be null");
        }
    }

    /**
     * JPA {@link LockModeType}를 {@link ResolvedLock}으로 매핑한다. {@code null}은 거부한다.
     */
    public static ResolvedLock resolve(LockModeType lockModeType) {
        Objects.requireNonNull(lockModeType, "lockModeType must not be null");
        return switch (lockModeType) {
            case NONE -> new ResolvedLock(LockMode.NONE, false, false);
            case READ, OPTIMISTIC -> new ResolvedLock(LockMode.NONE, true, false);
            case WRITE, OPTIMISTIC_FORCE_INCREMENT -> new ResolvedLock(LockMode.NONE, true, true);
            case PESSIMISTIC_READ -> new ResolvedLock(LockMode.FOR_SHARE, false, false);
            case PESSIMISTIC_WRITE -> new ResolvedLock(LockMode.FOR_UPDATE, false, false);
            case PESSIMISTIC_FORCE_INCREMENT -> new ResolvedLock(LockMode.FOR_UPDATE, true, true);
        };
    }

    /**
     * 주어진 {@link LockModeType}가 SELECT에 붙일 Nova {@link LockMode}(pessimistic 강도)를 반환한다.
     * OPTIMISTIC 계열은 물리적 lock 절이 없으므로 {@link LockMode#NONE}이다.
     */
    public static LockMode toLockMode(LockModeType lockModeType) {
        return resolve(lockModeType).lockMode();
    }

    /**
     * 이 모드가 {@code @Version} 속성을 필요로 하는지(버전 검증 또는 강제 증분). {@code true}인데 엔티티에
     * {@code @Version}이 없으면 표현 불가 조합이므로 호출부가 fail-fast 해야 한다.
     */
    public static boolean requiresVersion(LockModeType lockModeType) {
        ResolvedLock resolved = resolve(lockModeType);
        return resolved.versionCheck() || resolved.forceIncrement();
    }
}
