package io.nova.benchmark;

import java.util.List;

/**
 * 두 ORM(Nova reactive, Hibernate ORM blocking)을 동일 시나리오로 구동하기 위한 공통 추상화. 모든 메서드는
 * 호출이 끝나면 작업이 DB에 반영된 상태로 블로킹 반환한다(reactive 구현은 내부에서 {@code block()}으로 완료
 * 대기) — 단일 스레드 latency 비교를 위해 동일한 동기 경계를 맞춘다.
 */
public interface OrmBenchmark extends AutoCloseable {

    String name();

    /** 스키마(테이블)를 만든다. */
    void setupSchema();

    /** 모든 행을 제거한다(시나리오 반복 사이 초기화). */
    void clear();

    /** {@code n}개의 새 행을 저장하고 생성된 id 목록을 반환한다. */
    List<Long> insert(int n);

    /** 각 id를 단건 조회한다. 조회된 행 수를 반환한다. */
    int findByIds(List<Long> ids);

    /** 전체 행을 조회한다. 행 수를 반환한다. */
    int findAll();

    /** 각 id의 행을 로드해 한 컬럼을 수정하고 저장한다. */
    void updateByIds(List<Long> ids);

    /** 각 id의 행을 삭제한다. */
    void deleteByIds(List<Long> ids);

    /**
     * {@code concurrency}개의 동시 요청으로 findById를 {@code totalOps}번 수행하고 초당 처리량(ops/sec)을 반환한다.
     * Nova는 reactive 파이프라인으로 적은 스레드에 다중 in-flight를, Hibernate는 스레드 풀로 블로킹 동시 실행을
     * 한다 — 두 ORM의 동시성 모델 차이를 드러낸다.
     */
    double concurrentFindOpsPerSec(List<Long> ids, int concurrency, int totalOps);
}
