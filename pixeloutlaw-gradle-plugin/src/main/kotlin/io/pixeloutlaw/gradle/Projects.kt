// We're using Gradle 6.8 features here
@file:Suppress("UnstableApiUsage")

package io.pixeloutlaw.gradle

import com.adarshr.gradle.testlogger.TestLoggerExtension
import com.adarshr.gradle.testlogger.TestLoggerPlugin
import com.adarshr.gradle.testlogger.theme.ThemeType
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin
import io.gitlab.arturbosch.detekt.DetektPlugin
import nebula.plugin.contacts.ContactsExtension
import nebula.plugin.contacts.ContactsPlugin
import nebula.plugin.responsible.NebulaResponsiblePlugin
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.AppliedPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin
import org.jetbrains.dokka.gradle.DokkaPlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Declare the minimum version of Java supported.
 */
internal val supportedJavaVersion = JavaVersion.VERSION_1_8

/**
 * Declare the minimum language version of Java supported (for use with toolchains).
 */
internal val supportedJavaLanguageVersion = JavaLanguageVersion.of(supportedJavaVersion.majorVersion)

/**
 * Fetches the [JavaToolchainService] extension from the project.
 */
internal val Project.javaToolchains get() = extensions.getByName<JavaToolchainService>("javaToolchains")

/**
 * Fetches a compiler instance from the [JavaToolchainService].
 */
internal val Project.javaToolchainCompiler
    get() = javaToolchains.compilerFor {
        languageVersion.set(
            supportedJavaLanguageVersion
        )
    }

/**
 * Runs an [action] after the Java Gradle plugin has been applied.
 */
internal fun Project.withJavaPlugin(action: AppliedPlugin.() -> Unit) = pluginManager.withPlugin("java", action)

/**
 * Runs an [action] after the Kotlin Gradle plugin has been applied.
 */
internal fun Project.withKotlinJvmPlugin(action: AppliedPlugin.() -> Unit) =
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm", action)

/**
 * Runs an [action] after the Maven Publish Gradle plugin has been applied.
 */
internal fun Project.withMavenPublish(action: AppliedPlugin.() -> Unit) =
    pluginManager.withPlugin("maven-publish", action)

/**
 * Runs an [action] after the Nebula Maven Publish Gradle plugin has been applied.
 */
internal fun Project.withNebulaMavenPublish(action: AppliedPlugin.() -> Unit) =
    pluginManager.withPlugin("nebula.maven-publish", action)

/**
 * Configures the project to publish to Maven Central.
 */
internal fun Project.publishToMavenCentral() {
    val (mvnName, mvnUrl) = if (version.toString().endsWith("-SNAPSHOT")) {
        "ossrhSnapshots" to uri("https://oss.sonatype.org/content/repositories/snapshots/")
    } else {
        "ossrhReleases" to uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
    }

    configure<PublishingExtension> {
        repositories {
            maven {
                credentials {
                    username = System.getenv("OSSRH_USERNAME")
                    password = System.getenv("OSSRH_PASSWORD")
                }
                name = mvnName
                url = mvnUrl
            }
        }
    }

    pluginManager.apply(SigningPlugin::class.java)

    // Signing will only occur if trying to publish the JAR.
    configure<SigningExtension> {
        setRequired({
            gradle.taskGraph.hasTask("publish")
        })
        val signingKey: String? by project
        val signingPassword: String? by project
        // Uses ASCII-armored keys (typically provided on GitHub Actions)
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
}

/**
 * Configures anything specifically related to publishing to Maven.
 */
internal fun Project.applyMavenPublishConfiguration() {
    withMavenPublish {
        publishToMavenCentral()
    }
}

/**
 * Configures all Maven publications to be signed and have POM contents meet Maven Central requirements.
 */
internal fun Project.configurePublicationsForMavenCentral() {
    configure<PublishingExtension> {
        publications.withType<MavenPublication> {
            // Signing the binaries
            extensions.getByType<SigningExtension>().sign(this)

            // POM contents for Maven Central
            pom {
                mitLicense()
                addCompileOnlyDependenciesAsProvided(this@configurePublicationsForMavenCentral)
            }
        }
    }
}

/**
 * Configures anything specifically related to modifying Maven publications.
 */
internal fun Project.applyNebulaMavenPublishConfiguration() {
    withNebulaMavenPublish {
        configurePublicationsForMavenCentral()
    }
}

/**
 * Adds the Test Logger plugin so that test logs are prettier.
 */
internal fun Project.applyTestLoggerPlugin() {
    pluginManager.apply(TestLoggerPlugin::class.java)
    extensions.getByType(TestLoggerExtension::class.java).apply {
        theme = ThemeType.MOCHA
        showSimpleNames = true
        showStandardStreams = true
        showFailedStandardStreams = true
        showSkippedStandardStreams = false
        showPassedStandardStreams = false
    }
}

/**
 * Configures anything specifically related to Kotlin.
 */
internal fun Project.applyKotlinConfiguration() {
    withKotlinJvmPlugin {
        pluginManager.apply(DetektPlugin::class.java)
        pluginManager.apply(DokkaPlugin::class.java)

        configure<SpotlessExtension> {
            kotlin {
                target("src/**/*.kt")
                ktlint("0.40.0")
                trimTrailingWhitespace()
                endWithNewline()
                if (file("HEADER").exists()) {
                    licenseHeaderFile("HEADER")
                }
            }
        }

        tasks.withType<KotlinCompile> {
            dependsOn("spotlessKotlinApply")
            kotlinOptions {
                jdkHome = javaToolchainCompiler.get().metadata.installationPath.asFile.absolutePath
                javaParameters = true
                jvmTarget = supportedJavaVersion.toString()
                useIR = true
            }
        }

        tasks.getByName("javadocJar", Jar::class) {
            dependsOn("dokkaJavadoc")
            from(buildDir.resolve("dokka/javadoc"))
        }
    }
}

/**
 * Configures anything specifically related to Java.
 */
internal fun Project.applyJavaConfiguration() {
    withJavaPlugin {
        pluginManager.apply(SpotlessPlugin::class.java)
        configure<SpotlessExtension> {
            java {
                target("src/**/*.java")
                googleJavaFormat()
                trimTrailingWhitespace()
                endWithNewline()
            }
        }

        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(supportedJavaLanguageVersion)
            }
        }

        tasks.withType<JavaCompile> {
            dependsOn("spotlessJavaApply")
            options.compilerArgs.add("-parameters")
            options.isFork = true
            options.forkOptions.executable = "javac"
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }
    }
}

/**
 * Configures anything that should always go on a project.
 */
internal fun Project.applyBaseConfiguration() {
    pluginManager.apply(ContactsPlugin::class.java)
    pluginManager.apply(NebulaResponsiblePlugin::class.java)

    configure<ContactsExtension> {
        addPerson("topplethenunnery@gmail.com")
    }
}
