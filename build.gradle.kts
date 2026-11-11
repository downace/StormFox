import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "com.github.downace.stormfox"
version = "0.3.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/**
 * The IDE the plugin is compiled against.
 *
 * `platform.localPath` overrides the released SDK for local development.
 */
val platformVersion: String? = providers.gradleProperty("platform.version").orNull
val localPlatformPath: String? = providers.gradleProperty("platform.localPath").orNull

/**
 * The oldest PhpStorm we support runs on Java 21, and the artifact has to load there, so the
 * bytecode target is pinned to that Java version and not to the one of the IDE we build against.
 */
val javaRelease: String = providers.gradleProperty("platform.javaRelease").getOrElse("21")

/**
 * The Kotlin language and API level of the oldest supported PhpStorm. The plugin uses the stdlib
 * of the IDE it runs in, so it must not reach for anything newer than that.
 */
val kotlinVersion: KotlinVersion = KotlinVersion.KOTLIN_2_0

dependencies {
    intellijPlatform {
        when {
            localPlatformPath != null -> local(localPlatformPath)
            platformVersion != null -> phpstorm(platformVersion)
            else -> error(
                "Set -Pplatform.version=<e.g. 2026.3> to build against a released PhpStorm, " +
                    "or -Pplatform.localPath=<path> to build against an installed one.",
            )
        }
        bundledPlugin("com.jetbrains.php")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
        testBundledPlugin("com.jetbrains.php")
    }

    testImplementation("junit:junit:4.13.2")
}

java {
    // The IntelliJ Platform Gradle Plugin asks for a toolchain that matches the Java version of the
    // target IDE (25 for PhpStorm 2026.3). The JDK that compiles the plugin does not have to be the
    // one the IDE runs on: the bytecode target below is what decides where the artifact loads. A
    // newer JDK with --release 21 produces the same bytes.
    toolchain {
        languageVersion.set(
            JavaLanguageVersion.of(JavaVersion.current().majorVersion.substringAfter('.').toInt()),
        )
    }
    sourceCompatibility = JavaVersion.toVersion(javaRelease)
    targetCompatibility = JavaVersion.toVersion(javaRelease)
}

tasks.withType<JavaCompile> {
    options.release.set(javaRelease.toInt())
}

kotlin {
    compilerOptions {
        apiVersion = kotlinVersion
        languageVersion = kotlinVersion
    }
}

// The IntelliJ Platform Gradle Plugin derives the Kotlin target from the toolchain, which is the
// JDK this machine has. The artifact has to load on PhpStorm 2024.3, so the target is the one the
// oldest supported IDE needs. This block comes last on purpose: it has to be registered after the
// plugin's own convention to win.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaRelease))
}

intellijPlatform {
    // The plugin has no Java sources, so there is no bytecode to instrument. Leaving it on would
    // pull org.jetbrains.intellij.deps:asm-all from packages.jetbrains.team, which this network
    // cannot reach.
    instrumentCode = false

    pluginConfiguration {
        id = "com.github.downace.stormfox"
        name = "StormFox"
        version = project.version.toString()

        vendor {
            name = "Sergey Malchits"
        }

        ideaVersion {
            // PhpStorm 2024.3. The plugin uses no platform API newer than that, except the
            // `prepareForFormatting` hook, which older IDEs simply never call.
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    buildSearchableOptions = false

    pluginVerification {
        ides {
            // The oldest IDE the plugin claims to support, and one in between. The build compiles
            // against the newest IDE, so the compiler cannot prove that the artifact fits the older
            // ones — the verifier does. The list can be narrowed with -Pplatform.verify.ides=2024.3.
            val versions = providers.gradleProperty("platform.verify.ides")
                .getOrElse("2024.3,2025.1")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            versions.forEach { create(IntelliJPlatformType.PhpStorm, it) }
            // The newest PhpStorm release, whatever it is at the moment: a fresh IDE must not break
            // the plugin.
            latest()
        }
    }
}

tasks {
    test {
        useJUnit()
    }
}

tasks.register("validateReleaseTag") {
    val tag = providers.environmentVariable("GITHUB_REF_NAME")
    val releaseVersion = project.version.toString()
    doLast {
        val value = tag.get()
        require(value.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))) {
            "Expected a version tag such as v1.2.3, got '$value'"
        }
        require(value == "v$releaseVersion") {
            "Tag '$value' does not match project version '$releaseVersion'"
        }
    }
}
