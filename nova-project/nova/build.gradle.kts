dependencies {
    api(project(":nova-project:nova-core"))
    api(project(":nova-project:nova-r2dbc"))
    api(project(":nova-project:nova-dialects:nova-dialect-mysql"))
    api(project(":nova-project:nova-dialects:nova-dialect-postgresql"))
    api(project(":nova-project:nova-dialects:nova-dialect-h2"))
    api(project(":nova-project:nova-dialects:nova-dialect-mariadb"))
    api(project(":nova-project:nova-dialects:nova-dialect-oracle"))

    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
