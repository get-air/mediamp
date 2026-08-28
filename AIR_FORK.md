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

1. Redact media sources and headers everywhere.
2. Replace the upstream Linux test skip with a live-window surface test and a
   bounded headless/preview fallback.
3. Map video rendition selection and live/DVR capability facts into Air's API.
4. Add Windows D3D and macOS Metal build/runtime gates.
5. Measure startup, dropped frames, CPU/GPU, memory and `vaapi-copy`; investigate
   DMA-BUF/EGL only with reproducible improvements.

Hosted Linux CI validates compilation, native assembly, zero-config loading and
reflection compatibility. Xvfb exposes llvmpipe GLX but Skiko selects its software
redrawer there, so it is not evidence for the production GLX surface. The live GLX
gate must run on a real display/GPU host; missing-surface failures are deduplicated
to keep that unsupported fallback from producing a per-frame log storm.
