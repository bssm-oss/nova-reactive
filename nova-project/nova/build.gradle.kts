dependencies {
    api(project(":nova-project:nova-core"))
    api(project(":nova-project:nova-r2dbc"))
    api(project(":nova-project:nova-dialects:nova-dialect-mysql"))
    api(project(":nova-project:nova-dialects:nova-dialect-postgresql"))
    api(project(":nova-project:nova-dialects:nova-dialect-h2"))
    api(project(":nova-project:nova-dialects:nova-dialect-mariadb"))
    api(project(":nova-project:nova-dialects:nova-dialect-oracle"))

    // 실제 r2dbc-h2 driver로 resolveDialect의 driver 이름 매핑 정확성을 검증한다.
    testImplementation("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
    // SchemaInitializerH2IntegrationTest가 StepVerifier로 reactive 파이프라인을 검증한다.
    testImplementation("io.projectreactor:reactor-test:3.7.3")

    // Wave1 교차 기능(cross-feature) E2E 전용 test-scoped 의존. 2차 캐시(nova-cache)와 Spring Data
    // Pageable/Sort 브릿지(nova-spring-data)는 aggregate 모듈의 production api가 아니라(선택적 add-on),
    // 여러 신규 기능이 한 애플리케이션 흐름에서 함께 도는지 검증하기 위해 test 소스셋에서만 참조한다.
    // testImplementation이므로 publication shape에는 영향이 없다.
    testImplementation(project(":nova-project:nova-cache"))
    testImplementation(project(":nova-project:nova-spring-data"))
    // nova-spring-data가 compileOnly로 두는 Spring Data 표준 타입(Pageable/Sort/PageRequest)을 테스트에서 직접 쓴다.
    testImplementation("org.springframework.data:spring-data-commons:3.4.5")
}
