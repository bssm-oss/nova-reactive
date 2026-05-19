dependencies {
    api(project(":nova-project:nova-core"))
    api(project(":nova-project:nova-r2dbc"))
    api("org.springframework.boot:spring-boot-autoconfigure:3.4.0")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.0")

    testImplementation(project(":nova-project:nova-dialects:nova-dialect-postgresql"))
    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-test:3.4.0")
    // ApplicationContextRunner의 AssertableApplicationContext가 AssertJ의 AssertProvider를 노출하므로
    // 컴파일 classpath에 AssertJ 클래스가 필요하다. 테스트는 AssertJ assertion을 직접 사용하지 않는다.
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
