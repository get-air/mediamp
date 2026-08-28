# Air desktop MPV adapter

`mediamp-air` is the optional desktop implementation of Air's
`com.getair.video` contract. Applications use `MediampDesktopBackendFactory`,
`MediampDesktopVideoPlayer`, and `MediampDesktopVideoSurface`; no mediamp or mpv
type crosses those public signatures.

The adapter deliberately stays outside the core `get-air/video` artifact so
Android, Apple-native, and browser builds do not resolve the desktop MPV stack.
It is built on the fork side because AGP's built-in Kotlin compiler controls the
fork plugins. The published adapter emits Kotlin 2.1 metadata and has a real
Kotlin 2.1 consumer compile gate.

```kotlin
val factory = MediampDesktopBackendFactory()
val player = factory.createDesktopPlayer()

@Composable
fun VideoSurface() {
    MediampDesktopVideoSurface(player, Modifier.fillMaxSize())
}
```

`PlaybackKind.Live` is always non-seekable even if mpv has cache data.
`PlaybackKind.SeekableLive` exposes the newest native seekable range and updates
as the DVR window moves. Audio, subtitle, and video selections are not written
optimistically: Air waits for mpv's next `track-list` confirmation.

The runtime artifact is separate from the adapter so platform packaging can
choose the correct native bundle:

```kotlin
implementation("com.getair:video-mediamp-desktop:<air-version>")
runtimeOnly("org.openani.mediamp:mediamp-mpv-runtime:<fork-version>")
```

`.github/workflows/air-packages.yml` is the manual, GitHub-only publication
path. It builds each native runtime on its matching hosted OS/architecture,
publishes those immutable modules to GitHub Packages, then publishes the JVM
API, aggregate runtime metadata, and adapter after every host succeeds. No
package is published merely by pushing code. Until that workflow completes for
a release version, consume this module through the Air workspace composite
build. Do not substitute a system mpv installation in production packages.
