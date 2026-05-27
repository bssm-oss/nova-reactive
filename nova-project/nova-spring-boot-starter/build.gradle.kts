dependencies {
    // nova-core/nova-r2dbc are already provided transitively by the nova
    // aggregate below; they are declared explicitly here to pin them as part of
    // the starter's stable surface API regardless of the aggregate's internals.
    api(project(":nova-project:nova-core"))
    api(project(":nova-project:nova-r2dbc"))
    // nova aggregate는 Nova 팩토리(resolveDialect)와 번들된 모든 dialect 클래스를 제공하므로
    // starter가 ConnectionFactory driver 메타데이터 기반 dialect auto-detection을 컴파일/런타임에 쓸 수 있다.
    api(project(":nova-project:nova"))
    api("org.springframework.boot:spring-boot-autoconfigure:3.4.0")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.0")

    // nova-dialect-postgresql is already on the test classpath transitively via
    // the nova aggregate api dependency, so no explicit test dependency is needed.
    testImplementation("org.springframework.boot:spring-boot-test:3.4.0")
    // ApplicationContextRunner의 AssertableApplicationContext가 AssertJ의 AssertProvider를 노출하므로
    // 컴파일 classpath에 AssertJ 클래스가 필요하다. 테스트는 AssertJ assertion을 직접 사용하지 않는다.
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
}
