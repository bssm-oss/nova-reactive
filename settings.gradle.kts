rootProject.name = "nova"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(
    "nova-core",
    "nova-dialect-postgresql",
    "nova-dialect-mysql",
)
