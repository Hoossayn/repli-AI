# Repli Keyboard — Pixel 10 Pro validation

Tested on 15 October 2026 on the connected Pixel 10 Pro, Android 17 / API 37, 1080 × 2410. Target: the FlorisBoard fork, `com.replyai.repli.keyboard.debug`. The updated debug build remains installed. Its existing data was preserved.

## Result

After the callback fix, **30/30 functional assertions and 8/8 controlled typing bursts passed**. The eight bursts injected **414 characters** through touchscreen key events at nominal 100 or 150 words/minute (five characters per word). Expected text matched exactly, including normal capitalization and `i` → `I`; there were no missing or extra characters in these fixtures.

The full Android unit suite passed **214 tests, zero failures or errors**. The debug APK built successfully and its saved signature was verified. No AndroidRuntime errors were returned for the tested keyboard process.

## Real-device issue found and fixed

The initial physical-device run failed immediate Backspace undo after `Teh` → `The `. Cursor logs showed duplicate intermediate acknowledgements during replacement and separator commits. The old acknowledgement queue consumed newer pending entries on a miss, and an obsolete cursor position could clear saved undo state.

The fix preserves pending expected edits when a callback does not match. Before accepting an unexpected position, the controller checks the input connection's live absolute selection where supported (Android 12/API 31 or newer). Obsolete callbacks are ignored; actual cursor movement still clears undo. Three queue regression tests cover duplicate acknowledgements, coalesced acknowledgements and clearing expectations after an external edit.

Physical verification then passed correction and undo for `teh`, `tomorrpw`, `becsuse` and `wouldnt`. A deliberate move to position 2 of `Thanks ` followed by Backspace produced `Tanks `, confirming that undo was cancelled rather than restoring an earlier typo.

This follow-up fix builds on `556b360a`. The source changes and regression coverage are included in the FlorisBoard fork; `repli-pixel-undo-fix.patch` also preserves the code change from the original test session.

## Functional coverage

Checks covered correction on space, immediate undo, name completion (`Tund` → selecting `Tunde`), next-word selection (`how are ` → `how are you `), quick key entry, and mid-word cursor changes. Common typos and contractions tested included `thnks`, `dont`, `wouldnt`, `becsuse` and `tomorrpw`.

`Tunde`, `Ola`, `Lagos`, `wahala`, `abeg`, `jollof`, `omw`, `gud`, `yeahh` and `helloo` were preserved in the tested contexts. These are selected regression fixtures, not a held-out estimate of general typing accuracy or a comparison against Gboard.

## Responsiveness measurements

Six traced bursts used three short messages at each rate. The keyboard's `Repli.wordSuggestions` scope had per-burst p95 durations of **8.30–14.12 ms** and an observed maximum of **20.71 ms**. The `Repli.wordCorrection` scope had per-burst p95 durations of **0.036–0.168 ms**. Suggestion scopes run in the background and include cache lookup/calculation; these measurements exclude queue delay, rendering and touch-to-screen latency.

Frame timing still needs work. Two further bursts ran **without tracing**:

- At a nominal **100 words/minute**, Android reported **58/192 frames (30.21%)** missing their deadline; reported frame-time p95 was **25 ms**.
- At a nominal **150 words/minute**, Android reported **27/335 frames (8.06%)** missing their deadline; reported frame-time p95 was **20 ms**.

The traced bursts also showed missed deadlines. The untraced controls establish that recording overhead alone does not explain the issue. Rates ran in a fixed order, with a warm debug process and changing runtime conditions, so these results do not establish that faster typing renders better. They are measurements of the debug build, not release-build performance. Android frame summaries are histogram-based; these are not key-to-visible-text percentiles.

A single post-test memory snapshot reported approximately **290 MiB PSS** for the debug process. The final thermal-service status was **0**. This session does not establish sustained memory growth, release memory usage or battery efficiency.

## Next priority

Profile keyboard rendering and editor-update work in a representative optimized build, then reduce missed frame deadlines. The existing input editor performs composing-region, commit and selection operations for key edits; it is a candidate to measure, not a proven root cause from this capture. Add a held-out typo/false-correction evaluation and broader host-app tests after that. A neural prediction model remains behind the responsiveness/quality gate.

## Phone restoration and files

The original default keyboard, enabled-keyboard list and hardware-keyboard display setting were verified restored. The temporary typing app, touch-runner file and UI dump were removed. Test fields requested no personalized learning. Real conversations were not used as test content.

Delivered files: `repli-keyboard-pixel-tested-debug.apk`, its SHA-256 checksum, `repli-pixel-test-results.json`, `repli-pixel-undo-fix.patch`, and selected `pixel-*.png` evidence images. The JSON retains per-fixture results and per-burst timing/frame summaries without the device serial number.
