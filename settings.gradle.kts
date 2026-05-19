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
    ":nova-project:nova-spring-boot-starter",
)
