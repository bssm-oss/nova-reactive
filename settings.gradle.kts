rootProject.name = "nova-build"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(
    ":nova-project:nova",
    ":nova-project:nova-core",
    ":nova-project:nova-r2dbc",
    ":nova-project:nova-dialects:nova-dialect-postgresql",
    ":nova-project:nova-dialects:nova-dialect-mysql",
    ":nova-project:nova-dialects:nova-dialect-h2",
    ":nova-project:nova-dialects:nova-dialect-mariadb",
    ":nova-project:nova-dialects:nova-dialect-oracle",
    ":nova-project:nova-spring-boot-starter",
    ":nova-project:nova-spring-data",
    ":nova-project:nova-metrics-micrometer",
    ":nova-project:nova-metamodel",
    ":nova-project:nova-cache",
)
