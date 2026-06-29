plugins {
    application
}

description = "Nova vs Hibernate Reactive side-by-side examples (not published)."

dependencies {
    // ----- Nova side -----
    implementation(project(":nova-project:nova"))
    implementation(project(":nova-project:nova-dialects:nova-dialect-h2"))
    runtimeOnly("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
    runtimeOnly("com.h2database:h2:2.2.224")

    // ----- Hibernate Reactive side -----
    implementation("org.hibernate.reactive:hibernate-reactive-core:2.4.0.Final")
    implementation("io.vertx:vertx-pg-client:4.5.10")
    implementation("io.smallrye.reactive:mutiny:2.6.0")

    // shared
    implementation("io.projectreactor:reactor-core:3.7.0")
    implementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("io.nova.example.nova.NovaReactiveExample")
}

tasks.register<JavaExec>("runNova") {
    group = "application"
    description = "Run the Nova reactive example against H2 in-memory."
    mainClass.set("io.nova.example.nova.NovaReactiveExample")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runHibernate") {
    group = "application"
    description = "Run the Hibernate Reactive example (requires PostgreSQL on localhost:5432)."
    mainClass.set("io.nova.example.hibernate.HibernateReactiveExample")
    classpath = sourceSets["main"].runtimeClasspath
}
