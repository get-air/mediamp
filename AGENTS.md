# Air mediamp fork guidance

This is Air's source fork of `open-ani/mediamp`, pinned initially at
`4aae5fa2956b5c0530704e0cd218aa75502584c6`. Preserve upstream history,
copyright notices, Apache-2.0 license text, and the `upstream` remote. Do not
copy surface files into `get-air/video`; Air adapts this fork behind its own
smaller `com.getair.video` API.

## Air priorities

- The production desktop path is the Compose/Skia surface ring: GLX/OpenGL on
  Linux, D3D shared textures on Windows, and IOSurface/Metal on macOS.
- Inline, arbitrary in-app PiP, and optional fullscreen are app layouts of one
  session. Never reintroduce a heavyweight child window as the general surface.
- Sources, headers, cookies, bearer tokens, local paths, and credential-bearing
  URLs must be redacted from `toString`, events, logs, exceptions, analytics,
  screenshots names, and cache keys.
- Every native/Kotlin mpv log must pass through `MPVLog` sanitization before a
  handler or stdout sees it. Do not bypass the sink or log raw media targets.
- Linux requires a live Skiko GLX environment before `vo=libmpv` can load.
  Headless smoke/preview tests must not pretend `createRenderContext()` can work
  without that environment. Add a bounded failure/fallback path and a real live
  Compose-window test.
- On AMD/Intel, `vaapi-copy` is the current honest decode path. The GLX-to-Skia
  ring is GPU shared, but end-to-end zero-copy must not be claimed until a
  measured DMA-BUF/EGL path lands.
- Keep the three-buffer publish/retire/ack protocol bounded. Producer threads
  never wait for Compose, and Compose never waits for a decoder frame.
- Upstream's public implementation escape hatch is not part of Air's app API.
  Air-specific capability mapping lives in the optional `mediamp-air` artifact,
  which implements only `com.getair.video` contracts. It stays on the fork's
  Kotlin 2.3 build side because Air's canonical Kotlin 2.1 build cannot compile
  newer Gradle-plugin metadata; applications still never receive mediamp types.

## Required local gates

- Initialize submodules recursively.
- Linux native builds require Meson, Ninja, NASM, pkg-config, OpenSSL, X11/GLX,
  VAAPI, and `ffnvcodec-headers`.
- Run the native assembly and production live demo against Air's H.264, HEVC,
  and AV1 Matroska corpus before surface changes.
- Add Windows/macOS host CI before claiming those render paths.
- Never use Robot, PipeWire, or desktop screenshot portals in automated tests.
  Use native frame diagnostics and app-owned window state.

See `AIR_FORK.md` and `get-air/video/docs/mediamp-evaluation-2026-08-28.md`.
