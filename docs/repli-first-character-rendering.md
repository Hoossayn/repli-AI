# First-character rendering follow-up

15 October 2026 · FlorisBoard fork · Pixel 10 Pro · Android 17 / API 37.

## Outcome

Implemented two small rendering changes: reuse key geometry when a case change leaves its bounds unchanged, and fit key labels through a reusable layout node. Matched empty-field trials showed fewer Android-owner measure scopes and less traversal work. **The longest first-character UI frame still measures about 14 ms; a repeatable latency improvement has not been established.** The first-character latency target remains open.

The final optimized build passed six 50-character typing bursts, 11 correction/suggestion checks and nine shift, caps, symbol and popup/cancellation checks. Android reported **6 missed deadlines out of 1,089 frames (0.551%)** across the six untraced bursts; per-run frame-time p95 ranged from **10 to 12 ms**. This is a new measurement, not a controlled improvement over the previous report's aggregate.

## Implementation

`ImeKeyboardBox.kt` previously read the current `TouchKey` from inside each key's layout modifier. A case-layer transition replaces those key objects, including keys whose bounds have not changed. The layout phase therefore depended on more state than the geometry required. The modifier now captures the bounds value and is remembered by those bounds. Layout still reads live key margins, and changed parent constraints still cause measurement. Input outputs and hit testing use the existing touch model.

`Modifiers.kt` previously implemented label fitting with `Modifier.composed`, remembered snapshot state and a graphics-layer modifier. It now uses a stateless modifier element with one layout node per usage. Measurement computes the scale and placement applies it to the drawing layer. It preserves the existing unbounded content measurement, 70% width limit, uniform X/Y scaling and centered transform. This applies to labels, hints and popup content. [Android's modifier migration guidance](https://developer.android.com/develop/ui/compose/custom-modifiers-node) describes the reusable-node approach.

Added constant, text-free trace scopes for keyboard, key, smartbar and candidate composition. These identify work without putting editor text or key labels into production timing markers. The standalone test host uses separate fixture markers and contains only synthetic test text.

A broader experiment that moved press observation into new per-key composable functions did not improve the case-change controls consistently and was discarded. That refactor is not included in the final patch. A label-node-only experiment also had variable frame times; no latency claim is based on it.

## Controlled first-character measurements

Both builds used the same release-derived `benchmark` variant, shell profiling without the debug flag, the same temporary package/settings, and effective ART `speed` compilation. The baseline was source commit `fdd9ae9e` plus the same four composition timing scopes. The final build adds the label-node and geometry changes. Installation preserved the temporary package's data/settings; each candidate was warmed before collection.

Three conditions typed one touchscreen `h` after setting the temporary field to:

- Empty text: expected `H`, including the subsequent case-layer transition.
- `hello. `: expected `hello. H`, exercising another sentence-start case transition.
- `hello `: expected `hello h`, the lowercase control.

Case order rotated each cycle. The primary comparison retains the first three complete cycles from each build: three trials per condition, nine per build. The final 60-second recording ended before the last cycle was fully covered because wireless control calls took longer than the timing guard allowed. The recording was recovered, and all retained fixture markers and their full response windows were verified. The entire last cycle was excluded from both builds before comparing metrics. All 12 final field-text assertions still passed; only nine have matched timing coverage.

For each retained fixture, analysis selected the main-thread `Choreographer#doFrame` scopes in the window from 5 ms before the host's text-change marker to 100 ms after it, then took the longest UI frame. Values below are medians across the three trials, not p95 values or touch-to-visible-text latency:

- Empty field: longest UI frame **13.69 → 14.10 ms**. Summed traversal-scope wall time in the response window **10.21 → 7.36 ms** (27.9% lower); Android-owner measure-scope entries **12 → 6**.
- After a sentence: longest UI frame **13.78 → 13.50 ms**; traversal-scope wall time **8.46 → 7.65 ms**.
- Lowercase control: longest UI frame **5.89 → 5.93 ms**; traversal-scope wall time **3.56 → 3.51 ms**.

The first-character update still composed 34 key boxes, compared with one in the lowercase control. Median key-composition time did not show a convincing reduction in the final comparison. The measured reduction is in repeated layout/traversal work, rather than proof of faster visible first-character response. Scope durations include nested work and may overlap; do not add them together or interpret them as CPU utilization. Measure-scope entry counts are not counts of distinct keys or independent full-window layout passes.

Analysis filtered the profiling keyboard and test host to Android user 0. Perfetto reported no nonzero error/data-loss statistics in the accepted baseline and final recordings. Effective ART compilation remained `speed`. The small sample, fixed build order, changing CPU/runtime conditions and wireless control delays limit causal and general-performance claims.

## Functional verification

The full unit suite passed **222 tests, zero failures, errors or skipped tests**. Debug and optimized benchmark APKs built successfully.

The six untraced bursts used actual touchscreen DOWN/UP events at nominal 100 or 150 words/minute and matched all 300 expected characters, including capitalization. Correction on space, Backspace undo, name/next-word selection, updated candidate slots, cursor movement, Pidgin preservation, contractions and an editor-context change during a held candidate press passed.

Additional checks passed manual Shift, return to lowercase, caps lock and release, symbol-layer entry and return, display of a held-key popup, cancellation without insertion, and the next key after cancellation. Selected screenshots were visually inspected for label/hint and popup rendering. These checks do not constitute a TalkBack audit, all-language layout coverage or an evaluation of general correction quality.

## Device handling and evidence

Tests used a local EditText host that requested no personalized learning; real conversations were not used. The existing Repli debug package was updated with `install -r`, preserving its data. The temporary profiling package is separate from that everyday installation.

The initial IME shell command selected the profiling keyboard for secondary user 10 while the test host was in user 0. The guard rejected the mismatch before typing. The secondary user's most recent non-test keyboard was restored using IME selection history, and only the newly added test-IME entry was removed from that user's enabled list. Subsequent switches explicitly targeted user 0. Restoration checks include both profiles.

The chat workspace's `outputs/first-character-rendering/` contains serial-free results, the matched traces and analysis, tested APKs and checksums, and selected visual evidence. Scratch harnesses, rejected experiments and full logs remain under `work/first-character/`. The trace windows, their coverage checks and the sampling limitation above must be retained when reusing these results.

The remaining performance target is case-layer recomposition and text/layout work. More detailed scopes around key appearance and unchanged hint/style updates are needed before choosing a larger rendering change. The current change does not establish lower touch-to-visible-text latency or solve all first-character deadline misses.

After installation, four additional smoke checks passed in the updated everyday debug keyboard: typing, correction on space, immediate undo and suggestion selection. No AndroidRuntime error lines were returned for its user-0 process or the final profiling process. The original default/enabled-keyboard configuration and hardware-keyboard preference were verified restored, including the secondary profile checks. The temporary profiling keyboard, typing host and named test files were removed; the updated everyday Repli installation and its existing data remain.
