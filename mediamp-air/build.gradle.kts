import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.util.Node
import groovy.util.NodeList
import org.gradle.api.publish.tasks.GenerateModuleMetadata

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    `maven-publish`
}

group = "com.getair"
version = providers.gradleProperty("AIR_ADAPTER_VERSION").getOrElse("0.1.0-SNAPSHOT")

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // AGP's built-in Kotlin plugin controls the fork build compiler. The adapter's
        // public ABI uses only Air/Compose/Kotlin types supported by Kotlin 2.1, so emit
        // 2.1 metadata for canonical Air consumers while keeping mediamp itself runtime-only.
        freeCompilerArgs.add("-Xmetadata-version=2.1.0")
    }
}

dependencies {
    api("com.getair:video:0.1.0-SNAPSHOT")
    api(libs.compose.ui)
    implementation(projects.mediampMpv)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "video-mediamp-desktop"
            pom.withXml {
                val dependenciesNode = (asNode().get("dependencies") as NodeList)
                    .firstOrNull() as? Node ?: return@withXml
                val stdlib = dependenciesNode.children()
                    .filterIsInstance<Node>()
                    .firstOrNull { dependency ->
                        val group = (dependency.get("groupId") as NodeList).firstOrNull() as? Node
                        val artifact = (dependency.get("artifactId") as NodeList).firstOrNull() as? Node
                        group?.text() == "org.jetbrains.kotlin" && artifact?.text() == "kotlin-stdlib"
                    } ?: return@withXml
                val versionNode = (stdlib.get("version") as NodeList).firstOrNull() as? Node
                versionNode?.setValue("2.1.10")
            }
        }
    }
}

// The Gradle module metadata would retain AGP's internal Kotlin stdlib version
// and override the compatibility POM above. This adapter is a plain JVM jar, so
// Maven metadata is sufficient and keeps Kotlin 2.1 consumers on their compiler
// stdlib while runtime resolution may still select mediamp's newer stdlib.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}
