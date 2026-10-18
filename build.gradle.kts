import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.4.21" apply false
}

// The middle number is the version of the model weights, as on npm and Hugging Face.
allprojects {
    group = "dev.apartejs"
    version = "1.1.0"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        // Java 8 bytecode: runs on every Android version and every JVM.
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        // Readable by every Kotlin compiler from 2.0 on, not only the one that built it:
        // an app on an older Kotlin cannot compile against newer metadata.
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            coreLibrariesVersion = "2.0.21"
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                languageVersion.set(KotlinVersion.KOTLIN_2_0)
                apiVersion.set(KotlinVersion.KOTLIN_2_0)
                freeCompilerArgs.add("-Xsuppress-version-warnings")   // 2.0 is deprecated, on purpose
            }
        }
        dependencies {
            "testImplementation"(kotlin("test"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }

        // ---- Maven Central: POM, sources and javadoc jars, GPG signatures
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        val publishing = extensions.getByType<PublishingExtension>()
        publishing.publications.create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set(provider { project.description })
                url.set("https://github.com/apartejs/aparte-secrets-kotlin")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("PaulRichez")
                        name.set("Paul Richez")
                        url.set("https://github.com/PaulRichez")
                    }
                }
                scm {
                    url.set("https://github.com/apartejs/aparte-secrets-kotlin")
                    connection.set("scm:git:https://github.com/apartejs/aparte-secrets-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/apartejs/aparte-secrets-kotlin.git")
                }
            }
        }
        // a local Maven repository, zipped by `centralBundle` for the Central Portal
        publishing.repositories.maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging"))
        }
        extensions.configure<SigningExtension> {
            useGpgCmd()
            sign(publishing.publications)
        }
        // signing needs a key: `signing.gnupg.keyName` in ~/.gradle/gradle.properties
        tasks.withType<Sign>().configureEach {
            onlyIf { project.hasProperty("signing.gnupg.keyName") }
        }
    }
}

// The upload bundle for central.sonatype.com: every artifact, its POM, checksums and signatures.
//
//     ./gradlew centralBundle   →   build/central-bundle.zip
val cleanStaging = tasks.register<Delete>("cleanStaging") {
    delete(layout.buildDirectory.dir("staging"))
}
tasks.register<Zip>("centralBundle") {
    subprojects.forEach { p ->
        dependsOn("${p.path}:publishAllPublicationsToStagingRepository")
        p.tasks.matching { it.name == "publishAllPublicationsToStagingRepository" || it.name.startsWith("publishMavenPublication") }
            .configureEach { dependsOn(cleanStaging) }
    }
    from(layout.buildDirectory.dir("staging"))
    exclude("**/maven-metadata.xml*")
    archiveFileName.set("central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
}
