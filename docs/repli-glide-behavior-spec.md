# Repli glide typing behavior specification

The first section records the static investigation. Implementation decisions and Pixel runtime observations follow at the end. The original read-only team handoff is retained separately.

This specification defines original Repli glide behavior and its public decoder interface. Repository integration means ordinary source modification from this handoff. The isolated CLI JSON-rebuild parity harness is a separate verification mechanism; its fixtures neither implement repository integration nor establish Gboard parity.

**Evidence and scope**

Real REA 6.7 static evidence exists for the supplied Gboard release. The collection used eight new inspections and nine reused exact-SHA records. No runtime execution, device access or production-app modification occurred. Artifact inventories are static observations; the six behavioral findings below are static derivations. There are no observed runtime results.

Source labels used below:

| Source | Finding and evidence IDs | Supported conclusion and limit |
|---|---|---|
| G01 | ev_5af0590640da77ee242d9b720cabf3518df1df70adac105a72c9ef5c7971ca46; ev_e25b34ec439d6d6037bc6aac0d0a6926fbfcad254dc1f3c43f54450411432d1b; ev_84b3905ae2191bc7bfc240346fb0e615fa949282fa286dd65950c859495bfb7f | Readiness, enablement and keyboard/layout availability gate processing. Event producers and field/language policies remain unresolved. |
| G02 | ev_60a0628089f0b5b76182df465a1f78bb0c3dbc01afccf9e1d141cc81baf6364e | Starting-key eligibility is checked. The complete eligibility rules are unresolved. |
| G03 | ev_84b3905ae2191bc7bfc240346fb0e615fa949282fa286dd65950c859495bfb7f | Processing uses historical/current coordinates, times, pointer identity and keyboard geometry. Duplicated regions and suspicious conditionals prevent an exact event mapping. |
| G04 | ev_705204a1f2c8807ae7f0c0eab645d304ab38f9d1454fd47e50cb02768450eea4; ev_337a7b19296a7d6abe774271338b020eae03168446a74158cff840e87934a107 | Admission distinguishes movement using time, distance, movement within one key and recent taps. Thresholds/defaults are unresolved; duplicated control flow and type-inference warnings limit interpretation. |
| G05 | ev_84b3905ae2191bc7bfc240346fb0e615fa949282fa286dd65950c859495bfb7f | Separate in-progress/final paths, overlay scheduling and lift/cancel cleanup exist. Every event branch, cadence, appearance and commitment behavior remain unproven. |
| G06 | ev_27682aa9999535866a2987bc38323d9e6739ff929a9694013befe66d5a1bd599; ev_aeb65608e1be84386c4efa9df3efbcfc8bba97a0626c8054d25555388e5bb457; ev_a233098cc29cdabedbc0541a97f4f3511f2c13e76cca6002ab0e404a7b95ad29 | Native request/response decoding, a separate preemptive interface and distinct gesture/tap timing metrics exist. These do not establish preview scheduling or native recognition/ranking behavior. |
| DOC | https://support.google.com/gboard/answer/2811346?hl=en, supplied observation dated 2026-10-21 | Official documentation describes sliding across letters, lifting to enter a word, suggestion replacement and incomplete language support. This is documentation, not runtime evidence. |
| RC | Supplied original Repli design contract | Authority for the Repli requirements and public interface below. |

A method record marked source-complete does not remove its decompiler warnings. Static findings motivate the design but do not prove identical Repli/Gboard behavior. No acceptance case below claims an exact Gboard candidate, cost or committed string.

**Public interface and data model — RC**

R-API-01. Preserve the public compatibility package `dev.patrickgold.florisboard.ime.nlp.latin.repli` and these API names. These are Repli public interfaces, not recovered artifact identifiers.

| Public type/interface | Contract |
|---|---|
| GlidePoint | Float fields `x` and `y`. |
| GlideKey | Char `letter`; Float `centerX`, `centerY`, `width`, `height`. |
| GlideCandidate | String `word`; Double `cost`. Smaller finite costs rank first. Cost is not a calibrated probability. |
| GlideDecoder | Constructed with `vocabulary: List<LexiconWord>`, using the existing project type. |
| GlideDecoder.decode | Accepts `points: List<GlidePoint>`, `keys: List<GlideKey>`, `limit: Int = 3`, and `contextWords: List<String> = emptyList()`; returns `List<GlideCandidate>`. |

R-API-02. Coordinates use physical pixels on the same current keyboard surface. Geometry comes from the actual layout; no fixed QWERTY geometry is assumed. [RC; G03 supports the geometry motivation.]

R-API-03. For supported valid inputs and positive limits, results contain only supplied-vocabulary words, have finite costs in ascending order, and contain no more than the requested limit. Identical inputs produce identical results. The decoder is Android-independent and fully offline. Exact costs and tie ordering are unresolved. [RC]

**Behavior requirements — original Repli choices**

R-GATE-01. Glide initially supports English text layouts and is enabled by default. It can be turned off in Gestures settings. Disabled mode preserves existing taps, long presses, layer-peek and multi-tap behavior. Password, URI, email, numeric, non-English and disconnected-editor contexts refuse glide. [RC; G01–G02 and DOC motivate gating but do not establish these Gboard policies.]

R-EVENT-01. A prospective gesture starts on one letter key. Jitter confined to that key remains an ordinary tap. Deliberate movement approximately three quarters of a key, crossing into another letter, activates glide before long-press and suppresses the starting-key tap. The exact boundary metric remains open; boundary cases are not deterministic acceptance assumptions. [RC; G02–G04 motivate admission.]

R-EVENT-02. Long-press, a second pointer, leaving the usable keyboard, editor change and cancellation must not insert a partial glide word. Cancelled work cannot later commit a word. These requirements do not specify unrelated normal input effects of those events. [RC; G03–G05 motivate separate lifecycle handling.]

R-DECODE-01. Completion evaluates the complete ordered geometric path against the already bundled, licensed offline vocabulary and current geometry. Shape order must influence recognition; endpoints alone are insufficient. Repeated letters require no mandatory loop. No Google dictionary, assets, model or code may be copied. [RC; G06 establishes only the existence of decoding in the inspected artifact.]

R-DECODE-02. Empty, invalid, nonfinite, degenerate, implausible and unsupported paths yield no word. Recognized candidates must not be fabricated for rejected input. Numeric plausibility boundaries and structural error reporting remain open. [RC]

R-DECODE-03. As project design choices, use resampling for comparable recordings with different sampling densities or timing; key-size normalization; tolerant endpoints; bounded candidate pruning and template caching; and moderate static frequency/context priors. These choices must support the intended recognition behavior without requiring a particular scoring formula or exact cost. Work, accepted input and cache bounds must be finite; their values remain open. [RC]

R-RESULT-01. Recognition runs off the UI thread with bounded work. Preview is throttled and never edits host text. Completed text and alternatives publish only while the editor, exact content/cursor, mode and gesture generation still match the request. Obsolete results cannot overwrite newer typing or another field. [RC; the result-identity rule is a Repli improvement proposal informed by G03 and G06, not a recovered guarantee.]

R-EDIT-01. Successful completion inserts the chosen word once. Duplicate delivery of the same completion must not insert it again. Consecutive glide words receive one separator between them. Exact separator ownership around pre-existing text or punctuation remains open. [RC; DOC supports lift-to-enter generally.]

R-EDIT-02. Selecting a displayed glide alternative replaces only its tracked word. Glide candidate provenance remains separate from tap autocorrect. [RC; DOC supports suggestion replacement generally.]

R-EDIT-03. If immediate word-backspace is enabled, immediate backspace deletes the tracked glide word. After an intervening edit or cursor movement, existing normal backspace behavior applies. Enabling/default policy, tracking expiry and separator cleanup remain open. [RC]

R-UI-01. Trail and preview can be disabled independently. Trail movement updates its overlay without invalidating existing key composition on every movement. Preview visibility does not authorize text edits. Exact styling and throttle timing remain open. [RC; G05 motivates overlay and preview/final separation.]

R-PRIV-01. Incognito uses only static vocabulary/context and never learns from the gesture. Paths, recognized words and host text do not leave the device or become retained typing history. Transient state needed for the current gesture and tracked replacement is not typing history. [RC; no corresponding Gboard privacy behavior was established.]

**Files, errors and acceptance notation**

R-FORMAT-01. The supplied decoder interface is typed, not a specified JSON production protocol. Reuse the project's existing LexiconWord type and bundled licensed vocabulary; their storage format is not supplied here. No native Gboard request format or proprietary resource format is part of this contract. [RC]

R-ERROR-01. For typed decoder inputs covered by R-DECODE-02, the observable result is an empty candidate list and no glide insertion. Missing JSON fields and wrong JSON types belong to an unspecified test-adapter boundary; this handoff does not invent its error object or exception behavior. Malformed structural cases are explicitly pending that boundary decision. [RC and interface limits]

R-TEST-01. The cases below are acceptance fixture descriptions expressed as JSON, not proposed production event messages. `expected_json` contains required observations or explicitly pending questions; omitted observations and numeric costs are not asserted. Matrix entries are independent runs from reset state. `baselineEquivalent` means comparison with the existing feature-disabled behavior. `normalBackspaceEquivalent` means comparison with existing normal backspace from the same pre-backspace editor state.

Fixture `corners` has 100-by-100 pixel letter keys centered at c=(50,50), a=(150,50), t=(150,150), r=(50,150). `cat_path` visits c,a,t; `cta_path` visits c,t,a. Fixture `repeat` places l=(50,50), e=(150,50), t=(250,50), r=(350,50), with the same key sizes. Its `leter_path` visits l,e,t,e,r without a loop at t. Vocabulary string lists denote synthetic entries in the existing LexiconWord type with equal static priors; they do not define that type's serialization.

Event fixtures inject the stated decoder candidates to isolate lifecycle/edit behavior. They do not claim those candidates were produced by Gboard or by a specified scoring formula. `activate` denotes admission already established, avoiding unresolved exact thresholds. Numeric fixture labels materialize typed floating-point values for direct decoder tests, not JSON numeric extensions. Cases with `status: pending` are not executable pass/fail parity oracles until their cited questions are resolved.

Case traceability: C01→R-GATE-01; C02→R-EVENT-01; C03→R-RESULT-01/R-EDIT-01/R-EDIT-02/R-UI-01; C04→R-EDIT-01; C05→R-EDIT-03; C06→R-EVENT-02; C07→R-RESULT-01; C08→R-GATE-01; C09→R-DECODE-01; C10→R-API-02/R-API-03/R-DECODE-03; C11→R-DECODE-01; C12–C13→R-DECODE-02; C14→R-PRIV-01; C15→R-DECODE-02/R-ERROR-01; C16→R-ERROR-01; C17→R-API-03; C18→R-API-03 and Q-R04.

The deterministic Repli acceptance assumptions above remain separate from unresolved Gboard-native behavior and runtime questions listed in `open_questions`.

## Project implementation contract

Improve the existing FlorisBoard-based Repli Android system keyboard. Implement original offline English glide typing for the active English alphabetic keyboard, using existing open-source Repli vocabulary, actual current key geometry, bounded background ranking, latest-request-only results, deliberate gesture activation that preserves taps/long presses, cancellation and editor/layout lifecycle invalidation, composable candidate replacement and sensible spacing. New features and thresholds are Repli product design choices, not claims of exact Gboard algorithms. Do not copy Gboard code, assets, dictionaries or models. Exact native ranker/model internals and all-language equivalence are excluded. Write observable requirements and JSON contract cases that can guide Kotlin integration and unit/device tests; unknown Gboard ranking outputs remain explicitly unasserted.

## Canonical test cases

```json
[
  {
    "id": "C01",
    "group": "ordinary",
    "input_json": "{\"target\":\"events\",\"enabled\":false,\"independentRuns\":[\"tap\",\"long_press\",\"layer_peek\",\"multi_tap\",\"letter_drag\"]}",
    "expected_json": "{\"eachRun\":{\"baselineEquivalent\":true,\"glideInsertions\":0}}"
  },
  {
    "id": "C02",
    "group": "ordinary",
    "input_json": "{\"target\":\"events\",\"enabled\":true,\"eligibleEditor\":true,\"layout\":\"corners\",\"events\":[\"down_c\",\"jitter_within_c\",\"up\"]}",
    "expected_json": "{\"baselineEquivalent\":true,\"glideActivations\":0,\"glideInsertions\":0}"
  },
  {
    "id": "C03",
    "group": "ordinary",
    "input_json": "{\"target\":\"events\",\"initialText\":\"\",\"eligibleEditor\":true,\"candidates\":[{\"word\":\"cat\",\"cost\":1},{\"word\":\"car\",\"cost\":2}],\"events\":[\"activate\",\"preview_result\",\"finish\",\"repeat_same_completion\",\"select_alternative_car\"],\"visibilityRuns\":[{\"trail\":true,\"preview\":true},{\"trail\":false,\"preview\":true},{\"trail\":true,\"preview\":false},{\"trail\":false,\"preview\":false}]}",
    "expected_json": "{\"eachRun\":{\"previewHostEdits\":0,\"completionInsertions\":1,\"insertedWord\":\"cat\",\"alternativeReplacementWord\":\"car\",\"replacementOutsideTrackedWord\":false,\"finalText\":\"car\"}}"
  },
  {
    "id": "C04",
    "group": "ordinary",
    "input_json": "{\"target\":\"events\",\"initialText\":\"\",\"eligibleEditor\":true,\"successfulGlides\":[\"cat\",\"car\"]}",
    "expected_json": "{\"committedWords\":[\"cat\",\"car\"],\"completionInsertions\":2,\"separatorsBetweenWords\":1}"
  },
  {
    "id": "C05",
    "group": "ordinary",
    "input_json": "{\"target\":\"events\",\"immediateWordBackspace\":true,\"successfulGlide\":\"cat\",\"independentRuns\":[[\"backspace\"],[\"intervening_edit\",\"backspace\"],[\"cursor_move\",\"backspace\"]]}",
    "expected_json": "{\"runs\":[{\"trackedGlideWordDeleted\":true},{\"normalBackspaceEquivalent\":true},{\"normalBackspaceEquivalent\":true}]}"
  },
  {
    "id": "C06",
    "group": "ordinary",
    "input_json": "{\"target\":\"events\",\"eligibleEditor\":true,\"gestureState\":\"active\",\"independentInterruptions\":[\"long_press\",\"second_pointer\",\"leave_usable_keyboard\",\"editor_change\",\"cancel\"],\"afterEach\":[\"deliver_old_result_cat\",\"lift\"]}",
    "expected_json": "{\"eachRun\":{\"partialGlideInsertions\":0,\"obsoleteGlideInsertions\":0}}"
  },
  {
    "id": "C07",
    "group": "ordinary",
    "input_json": "{\"target\":\"events\",\"requestSnapshot\":\"current\",\"independentChanges\":[\"editor_identity\",\"content_same_cursor\",\"cursor_same_content\",\"mode\",\"gesture_generation\"],\"afterEach\":[\"deliver_old_completion_cat\",\"deliver_old_alternatives\"]}",
    "expected_json": "{\"eachRun\":{\"obsoleteHostEdits\":0,\"obsoleteAlternativesPublished\":false}}"
  },
  {
    "id": "C08",
    "group": "edge",
    "input_json": "{\"target\":\"events\",\"enabled\":true,\"initialText\":\"日本語🙂\",\"independentContexts\":[\"password\",\"uri\",\"email\",\"numeric\",\"non_english_layout_日本語\",\"disconnected_editor\"],\"attempt\":\"letter_glide\"}",
    "expected_json": "{\"eachRun\":{\"glideActivations\":0,\"glideInsertions\":0,\"glideChangedExistingText\":false}}"
  },
  {
    "id": "C09",
    "group": "ordinary",
    "input_json": "{\"target\":\"decoder\",\"layout\":\"corners\",\"vocabulary\":[\"cat\",\"cta\"],\"contextWords\":[],\"limit\":2,\"independentPaths\":[\"cat_path\",\"cta_path\"]}",
    "expected_json": "{\"runs\":[{\"firstWord\":\"cat\"},{\"firstWord\":\"cta\"}],\"allReturnedCostsFinite\":true,\"costsAscending\":true}"
  },
  {
    "id": "C10",
    "group": "ordinary",
    "input_json": "{\"target\":\"decoder\",\"layout\":\"corners\",\"vocabulary\":[\"cat\"],\"path\":\"cat_path\",\"independentVariants\":[\"sparse_vertices\",\"dense_same_polyline\",\"same_geometry_different_pacing\",\"keys_and_path_offset_400_250\",\"keys_and_path_uniform_scale_2\",\"reposition_keys_and_follow_new_centers\"],\"repeatEachInput\":2}",
    "expected_json": "{\"eachRun\":{\"firstWord\":\"cat\",\"allReturnedCostsFinite\":true,\"repeatResultsIdentical\":true}}"
  },
  {
    "id": "C11",
    "group": "edge",
    "input_json": "{\"target\":\"decoder\",\"layout\":\"repeat\",\"vocabulary\":[\"letter\"],\"path\":\"leter_path\",\"contextWords\":[],\"limit\":3}",
    "expected_json": "{\"firstWord\":\"letter\",\"mandatoryLoopRequired\":false,\"allReturnedCostsFinite\":true}"
  },
  {
    "id": "C12",
    "group": "edge",
    "input_json": "{\"target\":\"decoder\",\"independentInputs\":[{\"layout\":\"corners\",\"vocabulary\":[\"cat\"],\"points\":[]},{\"keys\":[],\"vocabulary\":[\"cat\"],\"path\":\"cat_path\"},{\"layout\":\"corners\",\"vocabulary\":[],\"path\":\"cat_path\"}]}",
    "expected_json": "{\"runs\":[{\"candidates\":[]},{\"candidates\":[]},{\"candidates\":[]}]}"
  },
  {
    "id": "C13",
    "group": "edge",
    "input_json": "{\"target\":\"decoder\",\"layout\":\"corners\",\"vocabulary\":[\"cat\"],\"independentPoints\":[[[50,50]],[[50,50],[50,50],[50,50]],[[1000000000,1000000000],[1000000100,1000000000],[1000000100,1000000100]]]}",
    "expected_json": "{\"runs\":[{\"candidates\":[]},{\"candidates\":[]},{\"candidates\":[]}]}"
  },
  {
    "id": "C14",
    "group": "edge",
    "input_json": "{\"target\":\"events\",\"incognito\":true,\"eligibleEditor\":true,\"staticCandidate\":\"cat\",\"events\":[\"activate\",\"finish\",\"end_tracking\"]}",
    "expected_json": "{\"insertedWord\":\"cat\",\"gestureLearningUpdates\":0,\"transmittedPathsWordsOrText\":false,\"retainedTypingHistoryAdded\":false}"
  },
  {
    "id": "C15",
    "group": "malformed",
    "input_json": "{\"target\":\"decoder\",\"layout\":\"corners\",\"vocabulary\":[\"cat\"],\"basePath\":\"cat_path\",\"independentTypedMutations\":[\"point_x_nan\",\"point_y_positive_infinity\",\"point_x_negative_infinity\",\"point_x_float_overflow_to_infinity\"]}",
    "expected_json": "{\"eachRun\":{\"candidates\":[]}}"
  },
  {
    "id": "C16",
    "group": "malformed",
    "input_json": "{\"target\":\"adapter_boundary\",\"independentRequests\":[{\"points\":[{\"x\":50}],\"keys\":\"corners\"},{\"points\":\"cat_path\",\"limit\":\"three\"},{\"points\":null},{\"limit\":3}]}",
    "expected_json": "{\"status\":\"pending\",\"question\":\"Q-R05\",\"knownConstraint\":\"Malformed structural data has no specified successful decoder invocation or error representation.\"}"
  },
  {
    "id": "C17",
    "group": "ordinary",
    "input_json": "{\"target\":\"decoder\",\"layout\":\"corners\",\"vocabulary\":[\"cat\",\"cta\",\"car\"],\"path\":\"cat_path\",\"independentLimits\":[1,3,\"omitted\"],\"repeatEachInput\":2}",
    "expected_json": "{\"runs\":[{\"maximumCandidateCount\":1},{\"maximumCandidateCount\":3},{\"maximumCandidateCount\":3}],\"allReturnedCostsFinite\":true,\"costsAscending\":true,\"wordsFromVocabularyOnly\":true,\"repeatResultsIdentical\":true}"
  },
  {
    "id": "C18",
    "group": "edge",
    "input_json": "{\"target\":\"decoder\",\"layout\":\"corners\",\"vocabulary\":[\"cat\"],\"path\":\"cat_path\",\"independentLimits\":[0,-1]}",
    "expected_json": "{\"status\":\"pending\",\"question\":\"Q-R04\",\"knownConstraint\":\"Nonpositive-limit behavior is not specified by the supplied contract.\"}"
  }
]
```

## Open questions

- Q-R01 — Repli admission: What exact distance metric, key dimension, crossing rule and event ordering implement approximately three quarters of a key and activation before long-press? How are irregular keys and usable keyboard boundaries defined?
- Q-R02 — Repli decoding bounds: What finite limits apply to points, keys, vocabulary candidates, context, work and cached templates? What defines plausible paths, valid geometry, endpoint tolerance, supported characters and noise tolerance? What happens when these bounds are exceeded?
- Q-R03 — Repli ranking: What static priors, context normalization, repeated-letter treatment, rejection policy and deterministic tie order are chosen? Existing requirements do not establish numerical costs or rankings for ambiguous/noisy paths.
- Q-R04 — Repli limit boundaries: Does a zero or negative limit return an empty list or report an error?
- Q-R05 — Malformed adapter input: If the isolated JSON harness accepts raw request objects, how does it validate missing fields, wrong types and invalid values, and represent rejection? The typed API alone does not answer this; C16 remains pending.
- Q-R06 — Repli integration data: What are the existing LexiconWord fields, bundled licensed vocabulary source/format and required access conventions? Which English layouts are initially supported, and which existing setting/editor classifications identify eligible contexts?
- Q-R07 — Repli editing: How is the separator represented and owned around existing whitespace, punctuation and subsequent edits? How long do alternative replacement and immediate-backspace tracking remain valid? Is word-backspace enabled by default, and does deletion remove a separator?
- Q-R08 — Repli presentation: What are the preview throttle interval, trail appearance and lifecycle, and the defaults for independently disabling trail and preview?
- Q-G01 — Gboard runtime, outside deterministic Repli acceptance: What are the exact down/move/up/cancel and multitouch transitions? Decompiler warnings require corroboration before asserting complete event behavior.
- Q-G02 — Gboard runtime/native, outside deterministic Repli acceptance: What admission thresholds, latency, preview cadence, overlay styling and fade behavior apply?
- Q-G03 — Gboard native, outside deterministic Repli acceptance: How are repeated letters, ambiguous paths, confidence, rejection and candidate ranking handled? No exact Gboard candidate output is established.
- Q-G04 — Gboard runtime, outside deterministic Repli acceptance: What text is committed, what insertion/replacement boundaries apply, and how do spaces, punctuation, backspace and undo behave?
- Q-G05 — Gboard runtime, outside deterministic Repli acceptance: What password/private-field, unsupported-layout, locale and enablement policies apply?
- Q-G06 — Gboard runtime/privacy, outside deterministic Repli acceptance: Are sampled paths stored, used for training or transmitted? The collected methods establish no answer.

## Implementation decisions and Pixel observations — 21 October 2026

The original Repli implementation resolves the open design choices as follows. A stroke starts after at least 30 ms, crosses another letter and moves at least 0.75 starting-key width before its long press deadline. It retains at most 256 ordered points and refuses traces longer than 15 seconds. The pure decoder accepts at most 2,048 points, 64 keys and 150,000 vocabulary entries; it examines at most 8,192 words, ranks a shortlist of 128, caches 192 static templates and returns at most ten candidates. Template matching uses 48 arc-length samples with ordered, band-limited dynamic time warping; collinear reversals are retained. Costs are heuristic, not recognition probabilities.

Completion adds no trailing auto-space. A subsequent word owns its leading separator. Alternative selection replaces only the tracked word; immediate whole-word deletion also removes that owned separator. Original prefix characters remain. Shift is captured without consuming the user’s manual Shift press. Glide previews are latest-only; completed strokes are processed in order and rebase a following stroke only across the keyboard’s own matching commit. Fresh editor identity, content and cursor are checked before edits. Eligible no-suggestions chat fields follow Repli’s existing field policy; password/address/URI/numeric and non-English fields remain excluded.

On the physical Pixel 10 Pro with these installed versions, all 11 independently specified Repli scenarios passed (13 text/cursor snapshots): hello/world/letter/tomorrow, consecutive words and whole-word backspace, cancel, jitter, normal tap, noisy path, long prefix and immediate editor replacement. Gboard produced the same text in 12 snapshots. At the deletion snapshot, Gboard left `say hello ` while Repli restored `say hello`, removing the separator it inserted. Backspace began 782 ms after the second lift for Gboard and 776 ms for Repli. This is an intentional Repli edit policy, not a recovered Google implementation. Both keyboards recognized the tested synthetic repeated-letter paths without loops. The cancelled trace inserted nothing in this test.

These are observations of the existing English configuration and synthetic interpolation, not a human accuracy study, exact native-model reconstruction, latency benchmark or guarantee about all applications. The editor requests no personalized learning. The field-switch test changes editors immediately after lift, but does not force a particular overlap with decoder execution. Five secure IME settings, other profiles and removal of the owned temporary editor were verified after the run. The glide enable switch has a separate restoration journal.

Detailed runtime provenance and actual event timestamps: REA team run `20261021T183608Z-android-glide-verification-dc33e2`; feature run `20261021T175937Z-gboard-glide-feature/runtime-findings.json`. Remaining Gboard native scoring, model data, exact admission thresholds, full language support and every untested lifecycle branch remain unresolved.

### Final build verification

The final refinement moves the full context read to stroke activation; ordinary letter downs and subsequent motion use cached state. Queued strokes retain explicit manual Shift/Caps choices when rebased across the keyboard’s own preceding commit; only automatic capitalization follows the new context.

The final APK passed 291 app unit tests, 45 workflow tests, all 17 glide scenarios (23 snapshots) and four tap/autocorrect/undo regressions on the Pixel. Alternative replacement was observed as `say hello` → selecting displayed `help` → `say help` → whole-word backspace → `say `. Final device run IDs: `20261021T184652Z-android-glide-verification-bf2200`, `20261021T184814Z-android-glide-extras-03c90e`, `20261021T185028Z-android-glide-tap-regression-036327`. The installed APK digest and source snapshot match. All original secure keyboard settings and the glide switch were verified restored; the temporary editor is absent. Synthetic-case coverage does not establish human accuracy, all-app support or Gboard-native ranking equivalence.

### Default setting update

Following the user’s request, the glide preference now defaults to true. Explicit existing off/on choices remain authoritative; changing the fallback does not overwrite saved preferences. The disabled-mode acceptance cases remain applicable when glide is switched off.
