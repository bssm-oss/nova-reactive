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
}
