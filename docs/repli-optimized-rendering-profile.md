# Repli optimized-build rendering profile

15 October 2026 · Pixel 10 Pro · Android 17 / API 37 · source commit `e47c6c75`.

## Result

The release-derived profiling build completed **six untraced typing runs with 7 missed frame deadlines out of 1,097 frames (0.638%)**. Android's reported frame-time p95 ranged from **10 to 12 ms** across those runs. The median of the six per-run p95 values was 11 ms; this is not a pooled percentile or touch-to-visible-text latency.

All **eight measured typing bursts** (400 characters), **11 focused behavior checks**, and one separate 50-character CPU-sampling fixture matched the expected text. These selected fixtures do not estimate general correction accuracy or cover every host app.

The remaining priority is the first-character UI update and its recomposition/layout work. Prediction calculation was short in these warm captures. This investigation changes no production keyboard behavior.

## Build and compilation evidence

Used the existing `:app:assembleBenchmark` variant. It inherits release configuration, enables R8 optimization/minification and resource shrinking, and uses the separate package `com.replyai.repli.keyboard.bench`. The APK manifest has shell profiling enabled and no debuggable flag; the installed package flags also excluded `DEBUGGABLE`.

The build succeeded, including `minifyBenchmarkWithR8`. Before measurement, package-scoped compilation was requested with `speed`. Android ART dumps confirmed **`status=speed`, `reason=cmdline`, arm64** both before and after the runs. This differs from the preceding debug investigation, where the effective mode remained `verify`. It does not imply that every framework method or native library is ahead-of-time compiled.

APK: 58,844,539 bytes; one DEX, 10,645,052 uncompressed bytes. SHA-256: `58e0fec918f4a866b74abda90361500462c69f1de5e7421c123f3f828d314db0`. The saved APK's signature is checked alongside the delivered evidence. It is a locally signed profiling package, not a store release.

## Test method

The profiling keyboard was installed alongside the user's existing debug keyboard with fresh settings and the default English layout. A temporary local Android EditText host requested no personalized learning. Test text contained no real conversations.

Two 50-character warm-up bursts preceded the measurements. Each measured run typed `hello how are you doing today i hope you are well ` through actual touchscreen DOWN/UP events. Expected text was `Hello how are you doing today I hope you are well `. Checked key centers against a screenshot before injection. The six untraced intervals were 120, 80, 80, 120, 120 and 80 ms/key: nominally 100 and 150 words/minute using five characters per word.

Each run reset the field, allowed it to settle, reset the profiling package's graphics statistics, typed the fixture, and collected frame statistics before checking the final text. Results, in order:

- 120 ms/key: 2/191 missed deadlines; frame-time p95 11 ms.
- 80 ms/key: 1/175; p95 10 ms.
- 80 ms/key: 0/180; p95 12 ms.
- 120 ms/key: 3/184; p95 11 ms.
- 120 ms/key: 0/189; p95 11 ms.
- 80 ms/key: 1/178; p95 12 ms.

Two additional 12-second Perfetto recordings captured one 50-character burst at each rate, with frame timelines, UI scopes, scheduling and Binder events. Their graphics summaries were 2/185 and 1/178 missed deadlines; they are excluded from the untraced aggregate above. Perfetto reported no nonzero error/data-loss statistics in either trace. The recordings include some idle time around typing.

Thermal status was 0 before and after measurement. Refresh-rate settings were not forced. Expected frame budgets for the profiling keyboard in both traces were **16.600 ms**; these captures do not establish performance at a forced 120 Hz deadline.

## Where time went

In the 120 ms/key trace, the longest main-thread frame followed release of the first character. Its `Choreographer#doFrame` lasted **13.788 ms**: **5.566 ms** in animation/recomposition and **8.150 ms** in traversal. The frame started 1.101 ms after its expected start and completed 1.060 ms beyond its expected deadline. FrameTimeline classified it **App Deadline Missed / Late Present**.

The 80 ms/key trace's longest frame also followed the first character: **15.836 ms** on the main thread, including **7.103 ms** in animation/recomposition and **8.698 ms** in traversal. Its completion overran the expected deadline by 1.613 ms, but FrameTimeline classified it **Non Animating / Unspecified Present**. Do not count that classification as another explicit App Deadline Missed event. Each trace also contained one on-time frame with a Buffer Stuffing classification; the first combined that with App Resynced Jitter. Graphics summaries and FrameTimeline classifications are separate measurements.

Across the full captures, Android-owner measure scopes ran 564 and 612 times for 185 and 178 rendered frames. Relayout-window scopes appeared 94 and 102 times, respectively. These counts identify repeated measure/relayout work to investigate; the trace label alone does not prove that the keyboard visibly resized each time.

Background word-suggestion scopes ran 50 times per trace. Observed mean/max durations were **0.709/1.956 ms** and **0.607/1.587 ms**. Main-thread word-correction scope maxima were **0.012 and 0.014 ms**. These scopes exclude queue delay and rendering and represent warm fixture behavior.

A separate nine-second, application-scoped CPU recording used user-space `cpu-clock` sampling at 500 Hz with call stacks. It collected **627 samples, zero reported lost samples**. The main UI thread accounted for **66.19%**, RenderThread **25.36%**, and combined DefaultDispatch threads **3.67%** of recorded sample overhead. These are sample shares for this recording, not device CPU utilization. Inclusive stacks included Compose recomposition, view measurement, `ImeKeyboardBox` and key composition. Inclusive parent/child percentages overlap and must not be added together. The sample is too small to justify optimizing a single low-level function from its self percentage.

The first-character transition also changes the visible case layer and candidates. The correlation makes it a useful controlled experiment; these captures do not isolate which of those changes causes the longest recomposition.

## Next implementation target

1. Add narrow timing scopes around key-layer updates and keyboard/key composition in `ImeKeyboardBox.kt`, plus candidate updates. Compare first-character entry with entry in an already lowercase word, keeping settings and compilation fixed. Preserve key output and touch geometry.
2. Use those scopes to reduce full-keyboard recomposition or repeated measurement on that transition. Investigate the accompanying root-view relayouts before changing editor/input-connection behavior.
3. Re-run the same untraced fixtures and correction/undo/candidate checks. Require a repeatable improvement in the first-character frame with no correctness regression. Then broaden to other host apps and directly measure touch-to-visible-text latency.

No production rendering change is justified solely by the earlier debug frame percentages. The fresh profiling package has different settings/data from that debug package, so the new numbers are an optimized baseline, not a controlled before/after improvement attributable to the smartbar commit. Cold start, sustained battery use, forced 120 Hz operation and general correction quality remain outside this capture.

## Reproduction and saved evidence

Build `:app:assembleBenchmark`, install its APK, enable/select `com.replyai.repli.keyboard.bench/dev.patrickgold.florisboard.FlorisImeService`, and compile only that package with `cmd package compile -m speed -f`. Verify `pm art dump` and package flags rather than trusting command success alone.

Use the local typing host and touchscreen runner, check keyboard coordinates, and warm the same fixture before collecting untraced graphics statistics. The reusable trace configuration is `utils/profiling/repli-render.pbtxt`. Pass it to `perfetto --background-wait --txt -c -` through standard input, with a named output under `/data/misc/perfetto-traces/`; wait for the bounded recording to finish before pulling the trace. Android restricted Perfetto's access to a config under `/data/local/tmp`, so the standard-input route was used. CPU sampling used `simpleperf record --app com.replyai.repli.keyboard.bench`; direct PID sampling was denied, while the application-scoped profileable path succeeded. No device security settings were changed.

The chat workspace's `outputs/optimized-rendering/` contains the report, serial-free JSON summary, tested APK/checksum/signature result, both Perfetto traces, SQL analysis, CPU sample/report, and frame summaries. Scratch harnesses and full logs remain under `work/optimized-profile/`. Local analysis used checksum-verified official Perfetto v58.2; no trace upload was required.

After testing, verify restoration of the original default keyboard, enabled-keyboard list including subtype settings, and hardware-keyboard preference. Remove the temporary profiling keyboard, typing host and named test/profiling files, retaining the user's existing Repli debug installation and data. Restoration results are recorded with the evidence.

Method references: [Android profileable builds](https://developer.android.com/guide/topics/manifest/profileable-element), [Perfetto FrameTimeline definitions](https://perfetto.dev/docs/data-sources/frametimeline).
