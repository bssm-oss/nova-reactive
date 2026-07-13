dependencies {
    api(project(":nova-project:nova-core"))
    api("org.springframework:spring-context:6.2.0")

    // Spring Data 표준 Pageable/Sort/Page/Slice 브릿지(SpringData* 어댑터, 표준 타입 오버로드)는
    // 이 타입들에 대해 컴파일된다. compileOnly = optional: 표준 타입 오버로드를 실제로 호출하는
    // 소비자만 spring-data-commons를 런타임에 추가하면 되고, Nova 자체 타입만 쓰는 소비자에게는
    // transitive 의존으로 강제되지 않는다(spring-context-only 유지). Spring Framework 6.2와
    // 정렬되는 Spring Data 2024.1 라인을 사용한다.
    compileOnly("org.springframework.data:spring-data-commons:3.4.5")

    testImplementation(project(":nova-project:nova-r2dbc"))
    testImplementation(project(":nova-project:nova-dialects:nova-dialect-h2"))
    testImplementation("io.projectreactor:reactor-test:3.7.3")
    testImplementation("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
    testImplementation("org.springframework.data:spring-data-commons:3.4.5")
}
