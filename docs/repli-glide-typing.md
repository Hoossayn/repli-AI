# Repli glide typing

Glide typing is enabled by default. You can turn it off or on under **Repli Settings → Gestures and glide typing → Enable glide typing**. It currently supports English alphabetic text layouts. An existing saved choice to turn glide off is preserved. Slide across the letters and lift to enter a word; repeated letters need no mandatory loop. The suggestion row offers replacements for the most recently inserted glide word.

## Behavior

- Ordinary taps and small finger jitter remain taps. A stroke activates after at least 30 ms, crossing another letter and travelling at least 0.75 of the starting key width. Long press takes priority if it activates first.
- A completed word has no trailing automatic space. The next glide adds one leading space when needed. Opening brackets and quotes do not receive a space. Glide refuses a cursor inside an existing word and address-like tokens.
- **Always delete word** makes immediate backspace remove the most recent glide insertion, including a separator owned by that insertion. Earlier user text is preserved. After another edit or cursor movement, normal deletion applies.
- Manual Shift capitalizes the word; caps lock capitalizes the whole word.
- Cancellation, a second finger during an active stroke, leaving the keyboard, changing fields or an obsolete editor snapshot discards that stroke. Progress previews never edit the host field.
- Preview and trail have independent switches. The trail fades separately from keyboard key composition. Preview refresh is throttled; recognition runs on a background worker. Completed strokes retain their order while newer previews replace old ones.

## Recognition and privacy

The original offline decoder uses the existing licensed Repli dictionary. It normalizes the actual keyboard geometry, resamples the complete ordered path and compares word shapes with bounded dynamic time warping. Static frequency and short context priors break geometric ambiguities. It does not contain Google code, models, dictionary data or other extracted assets.

Glide does not upload paths or text and does not add to adaptive learning, including in normal mode. Recognition holds the current bounded path and replacement context only in memory. Template caching contains static dictionary shapes. Password, email, URI, numeric and non-English fields do not use glide. Eligible chat fields follow Repli’s existing field policy; a host’s no-suggestions flag alone does not disable typing assistance.

## Limits and verification

This is a first offline English recognizer. Geometrically similar words and repeated-letter variants can remain ambiguous. Synthetic test traces establish covered behavior; they do not establish human accuracy or parity with Gboard's proprietary native ranking. Per-stroke coordinates are capped at 256 points with ordered compaction; the decoder also bounds vocabulary scanning, shortlist evaluation and template caches.

The behavior specification is [repli-glide-behavior-spec.md](repli-glide-behavior-spec.md). Its Gboard evidence, original Repli choices and unresolved vendor details are distinguished explicitly. The final build passed 17 Pixel glide scenarios, four tap/autocorrect regressions and 291 app unit tests. See [repli-glide-validation.md](repli-glide-validation.md) for evidence and limits.
