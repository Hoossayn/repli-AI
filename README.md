# Repli Keyboard

Repli is an open-source Android keyboard that reads the chat you're in, suggests replies that sound like you, and rewrites what you've typed — using **your own ChatGPT plan**. There are no Repli servers, no Repli account and no subscription: AI requests go straight from your phone to OpenAI under your account, and everything Repli learns about how you write stays encrypted on the device.

Repli is built from [FlorisBoard](https://github.com/florisboard/florisboard) (Apache-2.0) and adds Repli's suggestion engine, chat-grade autocorrect, adaptive learning and the reply tools on top of FlorisBoard's keyboard. The current layout is English QWERTY.

## How AI access works

Repli uses [Sign in with ChatGPT](https://developers.openai.com/siwc/token-sharing-open-source) for locally hosted apps. Tap **Continue with ChatGPT** in Repli, approve Repli in your browser, and come back: Repli receives an OAuth token for your account (never your password) and uses it to call `api.openai.com` directly, with storage off. Usage counts against your ChatGPT Plus or Pro plan; you can set a cap for Repli or disconnect it in [ChatGPT settings → Usage](https://chatgpt.com/settings/usage). Repli does not bill you for anything.

No ChatGPT plan? Paste your own OpenAI API key under **Settings → ChatGPT & API key**. It's stored encrypted on the phone and you pay OpenAI per request.

Without either, the keyboard still works: typing suggestions, autocorrect and adaptive learning are entirely on-device.

## What works

- **Replies from a screenshot of the chat**: tap *Suggest replies*, Repli reads the chat (the cropped screenshot is sent to OpenAI, read into speaker-tagged messages, and shown to you for review and speaker correction), you optionally add direction by typing or voice, then approve exactly what is sent and get three editable candidates. Replies are inserted as text; you press send.
- **Chat memory on the phone**: approved turns for a saved chat (up to 120) are kept Keystore-encrypted on the device, so later replies can draw on up to 24 earlier turns and your own outgoing style. Removing a chat deletes its memory.
- **Quick replies without capture**: when a notification from a saved chat was just opened, Repli offers to generate straight from that message using the saved memory.
- **Rewrite**: with text in the message box, rewrite it in a tone (casual, warm, direct, polite, shorter, clearer, fix grammar, Pidgin). Only your draft and your style cues are sent.
- **Chats and personas**: save chats, give each a persona (response guide + example replies), confirm notification senders before they're trusted.
- **Learn by scrolling**: capture your side of any chat while you scroll and learn only from the messages you approve.
- **Chat-grade autocorrect**: missing apostrophes (whys → why's, im → I'm), lone i → I, keyboard-aware two-edit fixes with a clear winner, chat-frequency ranking (thnks → thanks, not tanks), and no "fixing" of shorthand (omw, brb) or stretched words (yeahh). Capitalised words after a sentence start, Nigerian names, places and Pidgin from the bundled `repli-en_ng.supplement`, and any word you restore with Backspace are never autocorrected again.
- **Next-word suggestions** that blend your own usage with a chat-corpus n-gram prior, Repli's phrase hints and the dictionary; Yoruba, Hausa and Igbo completions; emoji you insert become a habit the keyboard offers after a sentence.
- **Diagnostics** under Settings → Repli: local, content-free counters for the reply pipeline. Never uploaded.
- Optional anonymous **crash reports** through Firebase Crashlytics (off in debug builds, toggle in Settings); never typed text or chat content.

## Build

Android SDK 37 and JDK 17:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Nothing else is required. The debug package is `com.replyai.repli.keyboard.debug`; release is `com.replyai.repli.keyboard`.

Crash reporting is optional: copy `firebase.local.properties.example` to the git-ignored `firebase.local.properties` and fill in the public Firebase mobile config for your own Firebase project. Release builds should upload their R8 mapping file to Firebase for readable stacks (the project initialises Firebase explicitly rather than from `google-services.json`); apply the Crashlytics Gradle plugin before a public release so reports carry a build ID.

Never commit OpenAI keys or any credential. The app itself never contains one: AI calls use the token or key the user enters on the device.

## Testing on a device

Sign in with ChatGPT needs a browser on the phone and a loopback callback on `127.0.0.1`, which Repli serves itself. If your phone restricts background data for Repli, the sign-in finishes when you return to the app ("Finish signing in"). The plan screen lets you choose which of your plan's models Repli uses; **Automatic** uses the first model ChatGPT lists.

## Privacy

Read the [Repli Keyboard privacy note](docs/repli-privacy.md): what leaves the phone, when, and to whom. Short version: only what you approve goes to OpenAI under your own account; nothing goes anywhere else.

## Contributing and licence

See [CONTRIBUTING.md](CONTRIBUTING.md) and [AI_POLICY.md](AI_POLICY.md). Repli Keyboard is licensed under the [Apache License 2.0](LICENSE), like FlorisBoard. The original FlorisBoard README is kept as [upstream documentation](UPSTREAM_README.md); its store links refer to FlorisBoard, not Repli.

Third-party data: next-word suggestions use an n-gram prior built from Google's Synthetic-Persona-Chat corpus (CC BY 4.0; aggregate counts only — see [provenance](third_party/provenance/repli-chat-ngrams.json) and `utils/build_chat_ngrams.py`). The bundled English dictionary is GPL-3.0 licensed; its source revision and hashes are in [dictionary provenance](third_party/provenance/repli-dictionary.json) and its licence text ships in the app assets.
