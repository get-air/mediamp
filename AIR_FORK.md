# Air fork status

Air selected this repository as the desktop native-runtime and Compose/Skia
surface base after a live Linux gate on 2026-08-28. Air's public player contract
remains in `get-air/video`; applications do not depend on mediamp backend types.

Initial verified path:

- bundled FFmpeg 8.0.1, dav1d 1.5.4, mpv 0.41.0 and JNI runtime;
- Skiko-shared GLX producer context and triple-buffered OpenGL surface ring;
- Air H.264, HEVC and AV1 Matroska fixtures rendered through the production
  Compose surface on an AMD Radeon RX 7900 XT;
- H.264 multitrack enumeration, embedded SRT/ASS, pause/play/seek/EOF/replay;
- native frame captures for all three codecs.

First fork work:

1. Media source objects, synchronous load failures, custom stream callback
   targets, and native mpv diagnostics are redacted. Log delivery is bounded to
   prevent malformed native/server output from creating an unbounded log line.
2. The real Linux Compose/GLX surface has a physical-GPU gate and repeated
   missing-surface failures are deduplicated. Hosted Xvfb still cannot replace
   that live gate because Skiko selects its software redrawer there.
3. `mediamp-air` maps confirmed audio/subtitle/video tracks and native
   seekability/cache ranges into Air's API. Plain live remains non-seekable;
   DVR uses the newest moving range.
4. Windows x64/ARM64, Ubuntu x64 and macOS x64 build/runtime/publication gates
   passed in GitHub run `33143165171`.
5. Measure startup, dropped frames, CPU/GPU, memory and `vaapi-copy`; investigate
   DMA-BUF/EGL only with reproducible improvements.

The optional adapter emits Kotlin 2.1 metadata and has been compiled from a
separate Kotlin 2.1 consumer. Mediamp implementation artifacts remain runtime
dependencies, so app source never resolves the fork's public `impl` escape
hatch. Full fork publication to GitHub Packages remains pending because native
runtime artifacts must be assembled on their matching hosts.

Hosted Linux CI validates compilation, native assembly, zero-config loading and
reflection compatibility. Xvfb exposes llvmpipe GLX but Skiko selects its software
redrawer there, so it is not evidence for the production GLX surface. The live GLX
gate must run on a real display/GPU host; missing-surface failures are deduplicated
to keep that unsupported fallback from producing a per-frame log storm.
