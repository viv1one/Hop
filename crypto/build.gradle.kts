plugins {
    // Version is declared once, apply false, in mobile/android/build.gradle.kts
    // (the root project of this Gradle build) — see the comment there.
    id("org.jetbrains.kotlin.jvm")
}

// Repositories are declared centrally in mobile/android/settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS) since :crypto is
// included as a subproject of that build — do not add a repositories{} block here.

dependencies {
    // Signal's official Double Ratchet implementation (Rust-backed, JNI). AGPLv3 —
    // already flagged to and accepted by the project owner (see task spec this module
    // was built against; not an open question).
    implementation("org.signal:libsignal-client:0.86.5")
    // AttestationProvider's contract is a suspend fun (real Play Integrity/App
    // Attest calls are async round-trips to Play Services / Apple's
    // infrastructure) -- pulled in as a normal (not test-only) dependency since
    // the real Android implementation will need it too, not just tests here.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // kotlin.test assertion helpers (assertEquals/assertFailsWith/etc.), wired to
    // run on the JUnit 5 platform declared above.
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Target JVM 17 bytecode (matching mobile/android/app's compileOptions) without
// requiring a JDK 17 *toolchain* to be installed on the build machine — these just
// set the target bytecode version, which any newer JDK's javac/kotlinc can emit.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
    // org.signal:libsignal-client:0.86.5 ships real Kotlin-compiled classes
    // (e.g. ECPrivateKey, ECPublicKey, PreKeyBundle) carrying Kotlin 2.1
    // metadata, and transitively requires kotlin-stdlib:2.1.0 — this repo's
    // centrally-pinned Kotlin Gradle plugin version is 1.9.24 (mobile/android/
    // build.gradle.kts), whose compiler can only read metadata up to 2.0.0.
    // Without this flag, :crypto (and, transitively via project(":crypto"),
    // :app) fails to compile at all against this exact, pinned libsignal-client
    // coordinate. This is a real version-compatibility finding, not a stylistic
    // choice — flagged to the project owner as needing an explicit decision:
    // keep this flag long-term (works today; unsupported/experimental compiler
    // flag, could break on a future libsignal-client bump that uses newer-only
    // Kotlin language features) vs. bump the central Kotlin plugin version to
    // 2.x repo-wide (a high-cost, version-it-explicitly change touching
    // protocol/ and mobile/android/app too, per CLAUDE.md's module-boundary
    // conventions — not something to do unilaterally from this module).
    kotlinOptions.freeCompilerArgs += listOf("-Xskip-metadata-version-check")
}
