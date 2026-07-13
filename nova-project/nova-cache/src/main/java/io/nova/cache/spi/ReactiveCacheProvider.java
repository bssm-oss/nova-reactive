package io.nova.cache.spi;

import reactor.core.publisher.Mono;

/**
 * region 이름으로 {@link ReactiveCache}를 발급하는 프로바이더 SPI. 2차 캐시 구현의 최상위 진입점이며,
 * 외부 캐시 통합(Redis, Caffeine-async 등)은 이 인터페이스를 구현해 {@link io.nova.cache.NovaCache}에
 * 주입한다. v1 기본 구현은 {@link io.nova.cache.SimpleReactiveCacheProvider}.
 *
 * <p>구현체는 스레드 안전해야 하며, 같은 region 이름에 대해 안정적인(동일) {@link ReactiveCache}
 * 인스턴스를 반환해야 한다.
 */
public interface ReactiveCacheProvider {

    /**
     * 주어진 region의 캐시를 반환한다. region이 없으면 생성한다. 동일 region 이름에 대해 같은 인스턴스를
     * 반환해야 한다.
     */
    ReactiveCache getCache(String region);

    /**
     * 모든 region의 엔트리를 비운다. 통합 테스트 초기화, native/bulk write 후 보수적 전역 무효화에 사용된다.
     */
    Mono<Void> clearAll();
}
