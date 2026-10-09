# Repli Keyboard

Repli Keyboard is an Android keyboard built from [FlorisBoard](https://github.com/florisboard/florisboard). This [fork](https://github.com/Hoossayn/florisboard-repli) brings Repli's offline English word suggestions, conservative autocorrect, and on-device adaptive learning to FlorisBoard's active keyboard. The current keyboard layout is English QWERTY. The UI uses Repli's theme (warm ivory paper `#FAF8F5`, ink `#27243A`, indigo accent `#6654D1`, lilac highlights) for the app, day keyboard stylesheet, and launcher branding.

## What works

- Word completions and next-word suggestions in eligible English text fields. Next words come, in order of trust, from your own adaptive model, a chat-corpus n-gram prior (trigram over bigram), Repli's phrase hints, and the dictionary; message openers come from the corpus.
- Candidate-tap replacement and autocorrect when a space is pressed.
- A bounded adaptive model for committed words and short phrases. It is encrypted with Android Keystore and stored in the app's no-backup directory.
- A **Typing → Adaptive learning** setting to stop learning or clear saved words. Password, email address, URL, no-suggestions, and incognito fields are excluded.
- A first-run **Repli account** page with Google, Apple, and email sign-in through Firebase. You can continue without an account to use the on-device keyboard. Cloud replies stay off unless the build is configured and you sign in; network is used to exchange the sign-in for a short-lived session and to send captured text you explicitly approve to your first-party backend.
- **Repli chats and personas**: save chats, choose a named persona for each chat or a one-off reply, and create or edit personas with response guides and example replies. Notification senders are suggested, never trusted until confirmed; groups and stale/ambiguous chats are excluded.
- **Repli replies**: cloud toggle with per-request approval, opt-in notification access (one-to-one WhatsApp/Telegram only), and guided capture via an accessibility overlay that never reads content, gestures, types, or sends. Screen frames stay in memory; captures finish on Done, timeout, or frame limit.
- Reply generation is cloud-only, through the first-party backend, and only after approval; there is no on-device reply model. Without a configured backend, sign-in or network, the panel says so and still lets you review captured context. Voice guidance transcription is on-device only; audio is never saved or uploaded.
- **Autocorrect that respects names**: capitalised words after a sentence start, Nigerian names, places and Pidgin from the bundled `repli-en_ng.supplement` list, and any word you restore with Backspace are never autocorrected again.
- Anonymous **crash reports** through Firebase Crashlytics when the build is configured, with a Settings toggle; never typed text or chat content. Release builds need their R8 mapping file uploaded to Firebase for readable stacks (the repository uses explicit Firebase initialization, not `google-services.json`, so upload it from the console or `firebase crashlytics:mappingfile:upload`).
- Keyboard integration: a **Suggest replies** smartbar action opens the Repli replies panel (sender confirm chips, manual chat picker, tap-to-insert suggestions, context review with speaker correction, reply-direction guidance with on-device voice input, and per-request cloud approval with full-screen review). Captures reuse the same consent → MediaProjection → OCR pipeline; replies insert as editable text and are never sent automatically. The panel follows the active Snygg keyboard theme (dedicated `repli-panel` elements in all bundled stylesheets).
- **Quick replies without capture**: when a notification from a saved chat was just opened, the panel offers to generate straight from that message; the backend adds up to 24 earlier approved turns, and the panel says how many it used (or suggests reading the chat once when there is no history yet). Screen capture stays the way to give Repli fresh, two-sided context.
- **Diagnostics** under Settings → Repli: local, content-free counters (captures, AI reads, failures, corrections, generation outcomes, time to first reply) to see where the reply pipeline is failing on a device. Reset any time; never uploaded.
- **Learn by scrolling** under Chats captures your side of any chat while you scroll, then learns only from messages you approve.
- Repli app flow: bottom navigation with **Home** (hero, keyboard setup, practice chat, reply/persona overviews), **Chats** (profiles and persona manager), and **Settings** (full keyboard settings plus Repli account, chats, and replies entries). Setup finishes on Home.

This is an early keyboard base. It is a separate Android app (`com.replyai.repli.keyboard`) and does not migrate learned data from the existing Repli app. The merged FlorisBoard alpha branch's older suggestion path was incompatible with its active keyboard, so this fork connects the Repli engine to the current keyboard controller.

## Build and test

Use Android SDK 37 and JDK 17, then run:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Optional cloud replies need a backend plus Firebase public mobile config. Copy `firebase.local.properties.example` to the ignored `firebase.local.properties` (or pass `-PASSISTED_*` Gradle properties / env vars) and fill `ASSISTED_REPLY_BACKEND_URL` plus the three required `ASSISTED_FIREBASE_*` values. Register each Android package and signing SHA-1 in Firebase, and set `ASSISTED_FIREBASE_APPLICATION_ID_DEBUG` to the debug app's ID for local builds. For Google sign-in, add `ASSISTED_GOOGLE_WEB_CLIENT_ID` from Firebase Authentication's Google provider. The debug package is `com.replyai.repli.keyboard.debug`; the release package is `com.replyai.repli.keyboard`. Enable Google in Firebase Authentication. For Apple sign-in, configure an Apple Service ID, Team ID, key ID, and private key in Firebase Authentication's Apple provider; use `https://<project-id>.firebaseapp.com/__/auth/handler` as the Apple return URL. These Apple credentials belong in the Firebase console, never in the app or repository. Without backend/Firebase configuration the welcome page offers offline keyboard use. Never commit OpenAI keys, backend signing secrets, or Firebase Admin credentials.

The debug build uses the application ID `com.replyai.repli.keyboard.debug`. On an emulator, the candidate row has been checked with `teh` → `the`, including candidate selection and correction on space. Adaptive storage, its off setting, and deletion were also checked on an emulator. Physical-device testing is still needed.

## Privacy and attribution

Read the [Repli Keyboard privacy note](docs/repli-privacy.md). The original FlorisBoard README is kept as [upstream documentation](UPSTREAM_README.md); its store and download links refer to FlorisBoard, not Repli Keyboard.

FlorisBoard source is Apache-2.0 licensed. Next-word suggestions use a bundled n-gram prior built from Google's Synthetic-Persona-Chat corpus (CC BY 4.0; aggregate counts only, see [provenance](third_party/provenance/repli-chat-ngrams.json) and `utils/build_chat_ngrams.py`). The bundled English dictionary is GPL-3.0 licensed; its source revision and hashes are in [dictionary provenance](third_party/provenance/repli-dictionary.json). The dictionary's license text is included in the app assets.
