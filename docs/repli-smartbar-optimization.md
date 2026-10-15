# Repli smartbar follow-up

Tested 15 October 2026 in the personal FlorisBoard fork, following correction-undo commit `5ea5e0e0`. Device: Pixel 10 Pro, Android 17 / API 37. Package: `com.replyai.repli.keyboard.debug`.

## Changes

The old suggestion path cleared candidates while each background request ran. Two separate controllers also expanded or collapsed the toolbar from different candidate sources, and repeatedly wrote its preferences. This could recreate candidate cells and change toolbar state during ordinary typing.

The follow-up uses one atomic suggestion state with an explicit pending flag. It retains candidates within the same editor/private session while new results calculate, clears them on editor changes and real cursor moves, and rejects pending or invalid suggestion commits. One app-owned observer now chooses toolbar expansion from the active prediction source, selection and inline suggestions. It writes preferences only when the target changes. Pending predictions preserve the current toolbar state.

Candidate gestures use updated callbacks and remain bound to the word touched when a slot changes. Action buttons observe visibility and mode values rather than every character or cursor position. Content capture remains enabled; the experiment disabling it was discarded.

## Verification

- Full unit suite: **222 passed, zero failures, errors or skipped tests**. Eight new tests cover toolbar expansion policy, pending work, active-engine ownership, selection and inline suggestions.
- Debug APK built successfully; signature and checksum recorded with the saved APK.
- **Five physical typing bursts passed**, each with 50 touchscreen-injected characters: three at 120 ms/key and two at 80 ms/key. All 250 measured characters matched the expected text exactly, including capitalization. Two additional warm-up bursts were not counted as assertions.
- **Eleven focused physical checks passed**: correction on space, Backspace undo, next-word selection, name completion, mid-word cursor movement, Pidgin preservation, contraction correction, updated candidate-slot selection and a changed editor context during a held candidate press.
- No AndroidRuntime error lines were returned for the final keyboard process. This does not establish coverage of every host app, accessibility service or typing pattern.

The temporary field requested no personalized learning and contained only test fixtures. Existing keyboard data was preserved. The original default keyboard, enabled-keyboard list and hardware-keyboard setting were verified restored. The temporary typing app, touch runner and UI dump were removed. The updated debug keyboard remains installed.

## Rendering evidence and limits

The comparison used the preceding undo-fix APK and this follow-up APK, on the same phone, with the same fixture, warm-up procedure and fixed test order. Each version had four untraced measured bursts plus one traced burst. A package-scoped speed-compilation request succeeded before each version, but the final Android ART dump reported `status=verify`. These are **debug-build measurements**, not confirmation of an ahead-of-time optimized or release build. Internal data labels ending in `aot` identify that attempted protocol only.

In the single traced 50-character burst, main-thread Compose recomposition events fell from **876 to 433**, and Android-owner measure events from **1,302 to 720**. Summed durations for those scopes fell from **830.205 to 628.486 ms** and **514.788 to 343.666 ms**, respectively. These scopes are not a complete CPU profile; related scopes may overlap and must not be added together. The capture supports less UI work in this fixture, not a general latency guarantee.

Frame timing did **not** improve in the four untraced bursts:

- Preceding build: **177/966 frames (18.323%)** missed their deadline; the median of the four reported per-run frame-time p95 values was **26.5 ms**.
- Follow-up build: **189/817 frames (23.133%)** missed their deadline; the corresponding median was **28 ms**.

The frame populations differ, the order was fixed and these are short debug runs with changing runtime conditions. The higher miss rate and percentile prevent claiming a smoothness improvement. The median above is not a pooled p95. Android frame summaries also do not measure touch-to-visible-text latency. Final thermal status was 0.

This change fixes conflicting toolbar state and unnecessary candidate rebuilding; the remaining frame deadline misses are unresolved. The next performance task is profiling a representative optimized build, including editor updates, key rendering and touch-to-display latency, before adding a larger prediction model. Broader host-app and held-out correction-quality checks also remain.

## Saved evidence

`repli-smartbar-test-results.json` contains the fixture assertions, frame summaries, trace-scope summaries and restoration checks without the device serial number. `repli-keyboard-smartbar-debug.apk` is the tested build; its SHA-256 checksum is saved alongside it. Detailed local scratch captures remain under `work/render-optimization/` in the chat workspace.
