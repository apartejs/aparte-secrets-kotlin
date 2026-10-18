// The runtime and the model (int4, ~114 KB): finds passwords, API keys, tokens and private keys
// in text. No dependency but the Kotlin standard library.

plugins {
    kotlin("jvm")
}

description = "Finds passwords, API keys, tokens and private keys in text, on-device: the aparte-secrets runtime with its model (int8, ~196 KB) bundled"
