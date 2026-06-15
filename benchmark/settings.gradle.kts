rootProject.name = "nova-benchmark"

// Standalone build: include the Nova build so `io.github.bssm-oss:*` dependencies
// resolve to the local project output (composite substitution) instead of a
// published artifact. Keeps the benchmark entirely out of the published build graph.
includeBuild("..")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
