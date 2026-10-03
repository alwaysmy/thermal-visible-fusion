# Third-party notices

The Gradle 8.11.1 wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`)
is redistributed from the official Gradle release sources under Apache License 2.0.
The original source headers and full upstream license file (including its bundled
third-party licensing information) are preserved. The wrapper JAR is unmodified.

- Source: https://github.com/gradle/gradle/tree/v8.11.1
- License: [third-party/gradle-LICENSE](third-party/gradle-LICENSE)
- Wrapper JAR SHA-256: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`
- Official JAR checksum: https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar.sha256
- Distribution ZIP SHA-256: `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`
- Official distribution checksum: https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256

Checksums were compared to the official endpoints when preparing this starter.
Gradle distributions and Android build dependencies are downloaded from their respective
official repositories at build time and remain subject to their own licenses.
No third-party thermal SDK binaries, documentation, demo source or signing credentials
are included in this public Android source tree. Optional local Guide builds include
the user's private SDK input and must not be treated as licensed for public redistribution.

Optional APK runtime dependencies have separate attribution in
`app/src/main/assets/THIRD_PARTY_RUNTIME.txt` and the full Apache License 2.0 text
in `app/src/main/assets/LICENSE-APACHE-2.0.txt`; these are accessible through the app's
“第三方组件与许可” button. Kotlin/Material/AndroidX notices do not license the vendor SDK.
