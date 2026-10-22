# Repli glide typing validation

Validated on 21 October 2026 using the physical Pixel 10 Pro, Android 17 / API 37, 1080 × 2410. The exact final debug APK remains installed with existing app data preserved. Its SHA-256 is `0fc07b4b2c0a68f1cfd4711f2eb37c14b7a55fe562a5af34a4c564406284a34d` (version 119, 0.6.0-debug+49cfd32e plus the recorded uncommitted glide implementation).

## Results

- 291 app unit tests and 45 REA workflow guard tests passed, with no failures. The 29 new app tests cover original path ranking, ordered reversals/repeated letters, real bundled-vocabulary fixtures, geometry/noise/density, invalid input, stroke admission and spacing.
- 17 Pixel glide scenarios passed, with 23 text/cursor snapshots: 11 comparison cases and 6 Repli-only cases.
- Four ordinary tap/autocorrect/backspace regression scenarios passed on the final build: `tge`, `becauae`, `tomorroe` and literal `hello`.
- The APK signature verified, its installed digest matched the frozen tested APK, and the implementation source matched the saved build snapshot.

The glide checks cover hello/world/letter/tomorrow, repeated letters without loops, noise, small jitter, ordinary taps, cancellation, long prefix, immediate editor replacement, consecutive swipes, whole-word deletion, typed delimiter/backspace, manual Shift, caps lock and sentence capitalization. The live candidate test swiped `hello`, selected the actual displayed alternative `help`, verified `say help`, then backspaced to the original `say ` prefix.

Gboard matched 12 of the 13 comparison snapshots. The deliberate difference is separator ownership: after `say hello world`, backspace left `say hello ` in Gboard and restored `say hello` in Repli. Earlier tap comparison results also retain Gboard's differing backspace behavior; their overall parity result is a failure, while Repli's independent contract passes. A matching vendor output is never used as the only correctness oracle.

## Responsiveness and privacy

The final refinement reserves only cached state on an ordinary letter down. Full editor context is read once when a glide activates and revalidated before edits. Geometry ranking runs in the background with finite work/cache bounds, while only the trail overlay observes high-frequency path changes. These implementation choices are not a measured input-latency result.

The decoder uses the existing licensed Repli dictionary and original code. No Google models, assets, dictionary or source were copied. Glide paths are transient memory and are not uploaded or used to update adaptive learning. The synthetic editor requested no personalized learning; that flag does not establish Gboard's internal policy.

## Restoration and use

Five original secure keyboard settings, other Android profiles, the original glide switch state and removal of the offline test editor were independently verified. Repli's updated build remains installed. Glide was originally off and was restored to off; enable it under **Repli Settings → Gestures and glide typing → Enable glide typing**.

## Scope and evidence

This is an original English recognizer validated on one installed layout. Similar word shapes remain ambiguous; human recognition accuracy, all languages/apps, multitouch/accessibility configurations and Gboard's native scoring internals are not established by these tests. Exact asynchronous decoder overlap is scheduling-dependent. The 18 canonical JSON cases in the behavior handoff are descriptions, not an additional 18-case automated parity run.

The behavior specification is [repli-glide-behavior-spec.md](repli-glide-behavior-spec.md). REA team evidence is retained in feature run `20261021T175937Z-gboard-glide-feature`; final device runs are `20261021T184652Z-android-glide-verification-bf2200`, `20261021T184814Z-android-glide-extras-03c90e` and `20261021T185028Z-android-glide-tap-regression-036327`. They retain actual touch uptimes, generated plans, independent expectations, REA package/device Evidence IDs, source snapshot and restoration journals.

## Enabled-by-default follow-up

The user subsequently requested glide enabled by default. The preference fallback is now true, with saved choices preserved. The updated debug APK built and was installed without clearing app data; its installed digest matched the saved build. The Pixel’s glide switch was already on before installation. A repeat UI check was stopped because the phone locked during the update. Recognition code is unchanged from the full validation above; those full-suite results remain attached to the preceding APK.
