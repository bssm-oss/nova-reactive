package io.nova.core;

import io.nova.query.storedprocedure.NamedStoredProcedureRegistry;
import io.nova.query.storedprocedure.ReactiveStoredProcedureQuery;
import io.nova.query.storedprocedure.StoredProcedureParameterDefinition;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
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

    // ---------------------------------------------------------------------------------------------
    // JPA 잠금(LockModeType) / find 오버로드 / FlushMode 계층 (Batch D)
    // 모두 additive default 메서드이며, 기본 구현은 관례에 따라 UnsupportedOperationException을 발행하거나
    // 안전한 no-op으로 완료한다. {@link SimpleReactiveEntityManager}가 실제 동작을 override한다.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@link LockModeType}를 적용해 id 단건을 조회한다(JPA {@code find(Class, Object, LockModeType)}).
     * PESSIMISTIC_WRITE/READ는 {@code FOR UPDATE}/{@code FOR SHARE} SELECT를, OPTIMISTIC 계열은 버전 검증
     * (필요 시 강제 증분)을 적용한다. 버전 의미가 필요한 모드를 {@code @Version} 없는 엔티티에 요청하면
     * {@link IllegalArgumentException}으로 fail-fast한다. 존재하지 않으면 빈 {@link Mono}.
     * <p><b>OPTIMISTIC 의미(설계상 의도):</b> {@code find}의 OPTIMISTIC은 별도 검증 쿼리를 발행하지 않는다 —
     * 방금 로드한 행의 버전이 곧 현재 값이므로, 검증은 이후 write(낙관락 UPDATE)나 명시적
     * {@link #lock(Object, LockModeType)}에서 이뤄진다. 이미 로드한 엔티티를 즉시 검증하려면 {@code lock}을 쓴다.
     */
    default <T> Mono<T> find(Class<T> entityType, Object id, LockModeType lockMode) {
        return Mono.error(new UnsupportedOperationException(
                "find(Class, Object, LockModeType) is not supported by " + getClass().getName()));
    }

    /**
     * properties 힌트를 받는 find 오버로드(JPA {@code find(Class, Object, Map)}). Nova는 현재 properties를
     * <em>인식만 하고 무시</em>하며, 동작은 {@link #find(Class, Object)}와 동일하다. {@code null} properties도
     * 허용한다(빈 맵과 동일 취급).
     */
    default <T> Mono<T> find(Class<T> entityType, Object id, Map<String, Object> properties) {
        return find(entityType, id);
    }

    /**
     * 이미 조회한(관리 중인) 엔티티에 주어진 {@link LockModeType}를 적용한다(JPA {@code lock}). OPTIMISTIC은
     * 현재 버전이 DB와 일치하는지 검증하고, *_FORCE_INCREMENT는 버전을 강제 증분하며, PESSIMISTIC_*는 해당
     * 행을 {@code FOR UPDATE}/{@code FOR SHARE}로 재조회해 잠근다. 버전 모드를 {@code @Version} 없는 엔티티에
     * 요청하면 fail-fast한다. 세션 밖에서도 SQL 기반 잠금/검증은 발행되지만 identity/dirty 의미는 없다.
     */
    default Mono<Void> lock(Object entity, LockModeType lockMode) {
        return Mono.error(new UnsupportedOperationException(
                "lock(Object, LockModeType) is not supported by " + getClass().getName()));
    }

    /**
     * 엔티티의 현재 잠금 모드를 반환한다(JPA {@code getLockMode}). Nova는 per-entity 잠금 상태를 추적하지
     * 않으므로, 세션에서 관리 중이고 {@code @Version}을 가진 엔티티는 {@link LockModeType#OPTIMISTIC},
     * 그 외에는 {@link LockModeType#NONE}으로 보고한다.
     * <p><b>계약 차이(기록):</b> JPA는 detached/비관리 엔티티에 대해 예외를 던지지만, Nova는 세션이 없거나
     * 관리 중이 아니면 예외 대신 {@link LockModeType#NONE}을 반환한다(리액티브 no-throw 등가).
     */
    default Mono<LockModeType> getLockMode(Object entity) {
        return Mono.error(new UnsupportedOperationException(
                "getLockMode(Object) is not supported by " + getClass().getName()));
    }

    /**
     * DB 재조회로 엔티티를 재적재({@link #refresh(Object)})한 뒤 주어진 {@link LockModeType}를 적용한다
     * (JPA {@code refresh(Object, LockModeType)}).
     * <p><b>기록:</b> PESSIMISTIC_* 모드는 refresh reload SELECT 후 잠금 재조회 SELECT가 이어져 SELECT를 두 번
     * 발행한다(refresh SELECT 안에서 바로 잠그는 최적화는 후속 과제).
     */
    default <T> Mono<T> refresh(T entity, LockModeType lockMode) {
        return Mono.error(new UnsupportedOperationException(
                "refresh(Object, LockModeType) is not supported by " + getClass().getName()));
    }

    /**
     * 이 매니저의 {@link FlushModeType}를 설정한 매니저를 반환한다(JPA {@code setFlushMode}의 리액티브 등가).
     * 리액티브 계약상 공유 매니저 인스턴스의 가변 상태를 피하기 위해, mutate 대신 지정한 모드를 가진 매니저를
     * 돌려준다(functional). {@link FlushModeType#COMMIT}이면 세션 스코프 안에서 쿼리 전 auto-flush를 억제하고
     * commit 시에만 flush한다. {@link FlushModeType#AUTO}(기본)는 쿼리 전 auto-flush를 유지한다.
     */
    default ReactiveEntityManager setFlushMode(FlushModeType flushMode) {
        return this;
    }

    /**
     * 이 매니저의 현재 {@link FlushModeType}를 반환한다(JPA {@code getFlushMode}). 기본은
     * {@link FlushModeType#AUTO}.
     */
    default FlushModeType getFlushMode() {
        return FlushModeType.AUTO;
    }

    // ---------------------------------------------------------------------------------------------
    // 저장 프로시저(@StoredProcedureQuery / @NamedStoredProcedureQuery) — W7, additive default 메서드.
    // 기본 구현은 UnsupportedOperationException을 발행하고 {@link SimpleReactiveEntityManager}가 override한다.
    // 리액티브 R2DBC 경로는 IN 파라미터 + result-set 프로시저를 지원하며, OUT/INOUT/REF_CURSOR는 실행 시
    // fail-fast 한다(드라이버 한계).
    // ---------------------------------------------------------------------------------------------

    /**
     * ad-hoc 저장 프로시저 호출 핸들을 만든다(JPA {@code createStoredProcedureQuery}의 리액티브 등가).
     * {@code parameters}는 IN 파라미터를 선언 순서대로 기술하며, result-set 이 없으면
     * {@link ReactiveStoredProcedureQuery#executeUpdate()}로 실행한다.
     */
    default ReactiveStoredProcedureQuery<?> createStoredProcedureQuery(
            String procedureName, List<StoredProcedureParameterDefinition> parameters) {
        return unsupportedStoredProcedure();
    }

    /**
     * ad-hoc 저장 프로시저 호출 핸들을 만들고 result-set 행을 {@code resultClass} 엔티티로 매핑한다.
     */
    default <T> ReactiveStoredProcedureQuery<T> createStoredProcedureQuery(
            String procedureName, List<StoredProcedureParameterDefinition> parameters, Class<T> resultClass) {
        return unsupportedStoredProcedure();
    }

    /**
     * ad-hoc 저장 프로시저 호출 핸들을 만들고 result-set 행을 사용자 지정 {@code mapper}로 매핑한다
     * ({@code @SqlResultSetMapping} 재사용 매퍼나 임의 투영에 사용).
     */
    default <T> ReactiveStoredProcedureQuery<T> createStoredProcedureQuery(
            String procedureName, List<StoredProcedureParameterDefinition> parameters,
            Function<RowAccessor, T> mapper) {
        return unsupportedStoredProcedure();
    }

    /**
     * {@link NamedStoredProcedureRegistry}에 등록된 명명 저장 프로시저 핸들을 만든다(JPA
     * {@code createNamedStoredProcedureQuery}의 리액티브 등가). 매니저는 엔티티 클래스 집합을 알지 못하므로
     * 명명 프로시저 해석은 호출자가 구성한 registry에 위임한다.
     */
    default ReactiveStoredProcedureQuery<?> createNamedStoredProcedureQuery(
            String name, NamedStoredProcedureRegistry registry) {
        return unsupportedStoredProcedure();
    }

    private <X> X unsupportedStoredProcedure() {
        throw new UnsupportedOperationException(
                "stored procedure queries are not supported by " + getClass().getName());
    }
}
