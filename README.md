# aparte-secrets-kotlin

Finds passwords, API keys, tokens and private keys in text, on Android and the JVM — the [aparte-secrets](https://huggingface.co/apartejs/aparte-secrets) model (196 KB, int8) bundled, offline, no dependency but the Kotlin standard library.

```kotlin
implementation("dev.apartejs:secrets:1.1.0")   // build.gradle.kts

val secrets = Secrets.load()   // load once, share across threads
secrets.detect("the wifi password is Sunflower77# for the guests")
// [Span(start=21, end=33, text=Sunflower77#, subtype=password, score=…)]

val masked = secrets.mask(message)               // "[PASSWORD_1]" instead of the value
val answer = askTheModel(masked.text)             // the secret never leaves the device
secrets.unmask(answer, masked.secrets)            // the values back, locally
```

Android 5.0 and later, any JVM 8 or later, usable from Java (`Secrets.load()`, `detect(text)`). Offsets are UTF-16 indices: `text.substring(span.start, span.end)`. The model reports raw spans; `minScore`, `ignore` (exact values) and `ignorePatterns` (regexes) are your policy, applied after it. A value found once is masked everywhere it appears. Any other model file of [the Hugging Face repository](https://huggingface.co/apartejs/aparte-secrets) loads with `Secrets.load(stream)` or `Secrets.load(file)`.

It is the same runtime as the JavaScript package [`@aparte/secrets`](https://www.npmjs.com/package/@aparte/secrets) and gives the same spans as the Python reference implementation — checked text by text on the reference set, on the JVM and on Android emulators.

## How good

On a chat test written after training (440 messages, English and French), strict span F1 **0.76** (OpenAI privacy-filter 0.53, detect-secrets 0.27, gitleaks 0.29). Every number, and the limits: [the model card](https://huggingface.co/apartejs/aparte-secrets).

## Tests

```bash
./gradlew test                                              # API tests
APARTE_SECRETS_REFS=references_int8.jsonl ./gradlew test    # + equality with the reference spans
```

`references_int8.jsonl` is on [Hugging Face](https://huggingface.co/apartejs/aparte-secrets/tree/main/references): it holds secret-shaped strings, so it is not in this repository.

## Versions

All aparte-secrets packages share one version, `<model version>.<package fix>`: 1.1.x carries model v1.1.

## License

MIT, Copyright (c) 2026 Paul Richez.
