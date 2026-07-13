package io.nova.core;

import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * JPA {@code jakarta.persistence.EntityManager}의 <em>리액티브 등가</em> 계약이다. blocking EntityManager를
 * 문자 그대로 구현하지 않고, 각 기능을 {@link Mono} 반환 API로 동등하게 노출한다 — 모든 메서드는 cold Mono이며
 * 구독 전에는 아무 것도 실행되지 않는다.
 *
 * <p><b>세션 스코프.</b> identity map/dirty checking/flush 의미를 가지는 연산(persist·merge·flush·clear·
 * detach·refresh·contains의 identity 부분)은 활성 영속성 세션 안에서만 의미가 있다. Nova에서 세션은
 * {@link ReactiveEntityOperations#inTransaction(Function)} 스코프에서만 존재하므로, 이 매니저의 연산은
 * {@link #inTransaction(Function)}(또는 하부 {@code operations.inTransaction}) 안에서 실행해야 한다. 세션
 * 밖에서 호출하면 저장/조회/삭제는 stateless로 동작하고, 세션 전용 연산(flush/clear/detach/contains)은 관리 중인
 * 상태가 없으므로 안전한 no-op(또는 {@code false})으로 완료한다 — 조용한 데이터 손상이 아니라 "관리할 상태 없음"의
 * 정직한 표현이다.
 *
 * <p><b>JPA 의미 매핑.</b>
 * <ul>
 *   <li>{@link #persist(Object)} — 신규(transient) 엔티티 INSERT. Nova {@link ReactiveEntityOperations#save(Object)}의
 *       insert 경로로 위임한다.</li>
 *   <li>{@link #merge(Object)} — 기존/detached 엔티티의 upsert. {@code save}의 insert-or-update 의미로 위임한다.</li>
 *   <li>{@link #remove(Object)} — DELETE.</li>
 *   <li>{@link #find(Class, Object)} — id 단건 조회(없으면 빈 Mono, JPA의 {@code null}에 대응).</li>
 *   <li>{@link #getReference(Class, Object)} — 조회하되 없으면 {@code EntityNotFoundException}으로 실패
 *       (JPA proxy 접근 시 예외에 대응; 리액티브 계약상 blocking proxy는 제공하지 않는다).</li>
 *   <li>{@link #flush()} — 세션 보류 변경을 즉시 DB로 반영.</li>
 *   <li>{@link #clear()} — 세션 identity map 비우기(관리 중 엔티티 전부 detach).</li>
 *   <li>{@link #detach(Object)} — 한 엔티티를 세션에서 분리(미flush 변경 폐기).</li>
 *   <li>{@link #refresh(Object)} — DB 재조회로 엔티티의 컬럼 상태를 재적재(보류 변경 폐기).</li>
 *   <li>{@link #contains(Object)} — 엔티티가 현재 세션에서 관리 중인지.</li>
 * </ul>
 */
public interface ReactiveEntityManager {

    /**
     * 신규(transient) 엔티티를 INSERT하고 관리 상태로 만든다. Nova {@link ReactiveEntityOperations#save(Object)}는
     * id 상태로 insert/update를 가르므로, id가 아직 없는(또는 존재하지 않는) 신규 엔티티에 대해 이 호출은 INSERT를
     * 수행한다. 생성 id는 반환된 엔티티에 다시 채워진다.
     */
    <T> Mono<T> persist(T entity);

    /**
     * detached/기존 엔티티의 상태를 반영한다(upsert). Nova {@code save}의 insert-or-update 의미로 위임한다.
     * JPA merge와 달리 별도 복사본이 아니라 저장된(생성 id가 채워진) 동일 인스턴스를 발행한다.
     */
    <T> Mono<T> merge(T entity);

    /**
     * 엔티티를 삭제한다(JPA {@code remove}). 세션이 있으면 먼저 세션에서 분리해 이 엔티티의 미flush 변경이
     * 뒤늦게 UPDATE로 나가지 않게 한 뒤 DELETE를 발행한다.
     */
    Mono<Void> remove(Object entity);

    /**
     * id로 단건 엔티티를 조회한다(JPA {@code find}). 존재하지 않으면 빈 {@link Mono}를 발행한다.
     */
    <T> Mono<T> find(Class<T> entityType, Object id);

    /**
     * id로 엔티티를 조회하되, 존재하지 않으면 {@code EntityNotFoundException}으로 실패한다(JPA
     * {@code getReference}의 접근-시-예외 의미의 리액티브 등가). 반환 {@link Mono} 자체가 지연(cold)이므로
     * 별도의 blocking proxy는 만들지 않는다.
     */
    <T> Mono<T> getReference(Class<T> entityType, Object id);

    /**
     * 활성 세션의 보류 변경을 즉시 DB로 밀어낸다. 세션이 없으면 no-op으로 완료한다.
     */
    Mono<Void> flush();

    /**
     * 활성 세션의 identity map을 비운다(관리 중 엔티티를 전부 detach). 세션이 없으면 no-op으로 완료한다.
     * 아직 flush되지 않은 변경은 폐기된다.
     */
    Mono<Void> clear();

    /**
     * 주어진 엔티티를 세션에서 분리한다(미flush 변경 폐기). 세션이 없거나 엔티티가 관리 중이 아니면 no-op이다.
     */
    Mono<Void> detach(Object entity);

    /**
     * DB에서 현재 컬럼 상태를 재조회해 주어진 엔티티 인스턴스에 in-place로 재적재하고 그 엔티티를 발행한다
     * (JPA {@code refresh}). 재조회 전에 이 엔티티를 세션에서 분리하므로 보류 변경은 폐기되고, 재적재 후
     * 다시 관리 상태(clean snapshot)로 편입한다. id가 {@code null}이면(transient) 실패하고, 행이 더 이상
     * 없으면 {@code EntityNotFoundException}으로 실패한다.
     * <p>
     * 스칼라/임베디드/FK 컬럼 상태만 재적재한다 — 연관(@OneToMany 등) 컬렉션의 in-place 재적재는 범위 밖이며
     * 필요하면 명시적 fetch로 다시 로드해야 한다.
     */
    <T> Mono<T> refresh(T entity);

    /**
     * 엔티티가 현재 세션에서 관리 중인지 반환한다(JPA {@code contains}). 세션이 없으면 관리 중인 상태가 없으므로
     * {@code false}를 발행한다.
     */
    Mono<Boolean> contains(Object entity);

    /**
     * 하부 {@link ReactiveEntityOperations#inTransaction(Function)}로 위임해 트랜잭션+세션 스코프 안에서 콜백을
     * 실행한다. 콜백은 이 매니저 인스턴스를 받으며, 그 안의 매니저 연산은 동일 세션(identity map/dirty/flush)을
     * 공유한다. 세션 flush는 commit 직전에 자동 발행된다.
     */
    <R> Mono<R> inTransaction(Function<ReactiveEntityManager, Mono<R>> work);

    /**
     * 하부 {@link ReactiveEntityOperations#inReadSession(Function)}로 위임해 단일 커넥션 read 스코프 안에서
     * 콜백을 실행한다. 트랜잭션/영속성 세션은 켜지 않으므로 identity map/dirty 의미는 적용되지 않는다.
     */
    <R> Mono<R> inReadSession(Function<ReactiveEntityManager, Mono<R>> work);
}
