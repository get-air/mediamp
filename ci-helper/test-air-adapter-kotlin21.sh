#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
video_root=$(cd "$repo_root/../video" && pwd)
test_root=$(mktemp -d "${TMPDIR:-/tmp}/air-mediamp-kotlin21.XXXXXX")
trap 'rm -r "$test_root"' EXIT

maven_repo="$test_root/repository"
consumer="$test_root/consumer"
mkdir -p "$maven_repo" "$consumer/src/main/kotlin"

"$video_root/gradlew" -p "$video_root" \
  -Dmaven.repo.local="$maven_repo" \
  publishJvmPublicationToMavenLocal \
  publishKotlinMultiplatformPublicationToMavenLocal \
  --console=plain

"$repo_root/gradlew" -p "$repo_root" \
  -Dmaven.repo.local="$maven_repo" \
  :mediamp-air:publishMavenPublicationToMavenLocal \
  --console=plain

cat >"$consumer/settings.gradle.kts" <<'EOF'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositories {
        maven(url = uri(file("repository")))
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
rootProject.name = "air-mediamp-kotlin21-consumer"
EOF

cat >"$consumer/build.gradle.kts" <<'EOF'
plugins { kotlin("jvm") version "2.1.10" }
kotlin { jvmToolchain(17) }
dependencies { implementation("com.getair:video-mediamp-desktop:0.1.0-SNAPSHOT") }
EOF

cat >"$consumer/src/main/kotlin/Consumer.kt" <<'EOF'
import com.getair.video.VideoBackendFactory
import com.getair.video.mediamp.MediampDesktopBackendFactory

fun desktopMpvFactory(): VideoBackendFactory = MediampDesktopBackendFactory()
EOF

cp -R "$maven_repo" "$consumer/repository"
"$video_root/gradlew" -p "$consumer" clean compileKotlin --console=plain
