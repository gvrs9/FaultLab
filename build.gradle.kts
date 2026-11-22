plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.dependency.management) apply false
}

allprojects {
    group = "com.faultlab"
    version = "1.0.0"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")

    // No toolchain or --release pin: use the installed JDK.
    // Keep application source compatible with Java 21 by project convention.
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
    }

    // Log4j2 is the only logging backend.
    configurations.all {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
        exclude(group = "ch.qos.logback")
    }
}
