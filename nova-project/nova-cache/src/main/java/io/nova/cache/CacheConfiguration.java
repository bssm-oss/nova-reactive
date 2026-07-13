package io.nova.cache;

import io.nova.cache.annotation.CacheConcurrencyStrategy;

/**
 * 한 엔티티 타입에 대해 해석된 2차 캐시 설정.
 *
 * @param cacheable 캐시 대상 여부. {@code false}이면 {@link CachingReactiveEntityOperations}가 이 타입을
 *                  캐시 없이 그대로 delegate로 통과시킨다.
 * @param keyType   캐시 키에 쓰는 <b>canonical(정규)</b> 엔티티 타입. 다형 계층에서 선언 타입
 *                  ({@code findById(Animal.class, id)})과 런타임 타입({@code save(dog)},
 *                  {@code dog.getClass()==Dog})이 달라도 동일한 캐시 키를 만들도록, 캐시 애너테이션을 선언한
 *                  <b>root-most 조상 클래스</b>로 정규화된다. 이 값이 read/write 양쪽의 키 컴포넌트를 일치시켜
 *                  다형 stale read를 막는다.
 * @param region    캐시 region 이름(cacheable일 때만 유효). 마찬가지로 canonical 타입 기준으로 정규화된다.
 * @param usage     동시성 전략(cacheable일 때만 유효).
 */
public record CacheConfiguration(
        boolean cacheable, Class<?> keyType, String region, CacheConcurrencyStrategy usage) {

    /**
     * 캐시하지 않는 타입을 나타내는 설정.
     */
    public static CacheConfiguration notCacheable() {
        return new CacheConfiguration(false, Object.class, "", CacheConcurrencyStrategy.READ_WRITE);
    }
}
