description = "Nova second-level cache — reactive read-through cache SPI + @Cacheable/@Cache wiring, " +
    "isolated from nova-core hub logic (decorator over ReactiveEntityOperations)."

dependencies {
    // 2차 캐시는 nova-core의 공개 계약(ReactiveEntityOperations/EntityMetadata/EntityMetadataFactory)과
    // jakarta.persistence 애너테이션(@Cacheable) 심볼을 참조한다. 의존은 단방향(cache -> core)이며
    // 반대 방향(core -> cache)은 절대 없다 — AGENTS.md rule #1 보존.
    api(project(":nova-project:nova-core"))

    testImplementation("io.projectreactor:reactor-test:3.7.3")

    // H2 in-memory 통합 테스트: 실제 r2dbc-h2 driver 위에서 read-through 히트/write invalidation을 검증한다.
    testImplementation(project(":nova-project:nova-r2dbc"))
    testImplementation(project(":nova-project:nova-dialects:nova-dialect-h2"))
    testImplementation("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
}
