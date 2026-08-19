plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    // Renamed from com.hop.spike -> com.hop.app: this is the app-level manifest
    // identity (namespace/applicationId), not the Phase 0 spike code's Kotlin
    // source package -- com.hop.spike.* (WifiDirectSpike/BleDiscoverySpike/
    // MainActivity) is untouched and stays under its existing package until it
    // is ported (not edited) and deleted in a later slice. Confirmed decision,
    // see docs/adr and the Stage 1 plan this task was scoped against: free to
    // rename now, real migration cost once a pilot build ships.
    namespace = "com.hop.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hop.app"
        // BLE requires 18+; WifiP2pManager requires 14+. Target modern devices only —
        // this spike isn't shipping, so no reason to carry old-API compat code.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-spike"
        // Was unset before this module had instrumented tests (com.hop.data's Room
        // DAO tests) -- JVM-unit-test-only until now.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by org.signal:libsignal-android:0.86.5's own AAR metadata
        // -- AGP's checkDebugAarMetadata task fails the build outright if
        // this isn't enabled for a consumer of that artifact (confirmed by
        // the actual build failure, not assumed/preemptive). :app's minSdk
        // is already 26, well above where desugaring usually matters, but
        // this is a hard requirement declared by the dependency itself, not
        // an optional opt-in here.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        // org.signal:libsignal-android:0.86.5 is declared directly below as
        // an `implementation` dependency of :app (NOT consumed transitively
        // via :crypto — crypto/build.gradle.kts declares
        // org.signal:libsignal-client, not -android, as `implementation`,
        // not `api`, so neither artifact lands on :app's compile classpath
        // on its own; confirmed by direct read, not assumed). :app needs its
        // own copy because RoomSignalProtocolStore (com.hop.data) implements
        // libsignal-client's SignalProtocolStore interface directly (see the
        // dependency block below for why -android specifically, not
        // -client). Its Java API classes are compiled with Kotlin 2.1
        // metadata, which this repo's centrally-pinned Kotlin 1.9.24
        // compiler can't read without this flag. See crypto/build.gradle.kts
        // for the full note — this is a real version-compatibility finding
        // that needs an explicit owner decision (accept this flag long-term
        // vs. bump the central Kotlin plugin version), not a silent
        // workaround.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Confirmed via web search as the correct Compose Compiler pairing for
        // this repo's centrally-pinned Kotlin 1.9.24 (mobile/android/build.gradle.kts)
        // -- the newer unified org.jetbrains.kotlin.plugin.compose Gradle plugin
        // needs Kotlin 2.0+ and isn't used here, consistent with the project's
        // prior explicit choice not to bump Kotlin repo-wide (see the
        // -Xskip-metadata-version-check note above).
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        // Real packaging bug found while exporting a build for the first time:
        // :app depends on :crypto (implementation(project(":crypto"))), and
        // :crypto depends on org.signal:libsignal-client (the desktop/server
        // JVM artifact, correct for :crypto's own plain-JVM unit tests -- see
        // its build.gradle.kts). `implementation` hides a dependency from a
        // downstream module's *compile* classpath, but NOT from the final
        // APK's packaging graph -- :app still bundles everything on its own
        // runtime classpath, including :crypto's transitive dependencies. The
        // result: alongside the correct org.signal:libsignal-android .so
        // files this app actually needs (see the dependencies block below),
        // the APK was also bundling libsignal-client's desktop-only
        // .dylib/.dll native libraries -- completely inert on Android, no
        // device can ever load them -- plus a duplicate "_testing" variant of
        // every native lib (Signal's internal test-suite build, not used by
        // this app either). Confirmed by unzipping a built APK: this exclusion
        // took it from ~660MB to its real size. Excluding these here, in
        // :app's own packaging block, rather than trying to stop :crypto from
        // depending on libsignal-client at all -- :crypto's own tests
        // genuinely need that desktop artifact, this is purely about what
        // :app ships to a device.
        jniLibs {
            excludes += "**/libsignal_jni_testing.so"
        }
        resources {
            excludes += setOf("**/*.dylib", "**/*.dll")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation(project(":protocol"))
    implementation(project(":crypto"))
    // org.signal:libsignal-android:0.86.5 -- deliberately NOT
    // org.signal:libsignal-client (the artifact crypto/build.gradle.kts
    // pins), even though :app needs the exact same Java API surface that
    // artifact provides. A real, load-bearing finding discovered while
    // getting RoomSignalProtocolStoreTest to actually pass on-device (not
    // just compile): org.signal:libsignal-client is the desktop/server JVM
    // artifact -- its jar embeds exactly one native binary,
    // libsignal_jni_amd64.so, loaded via a temp-extract-and-System.load
    // bootstrap that only works on a JVM, not inside an Android app process.
    // It has ZERO Android-ABI native libraries, so any Android runtime code
    // path that touches libsignal (e.g. constructing a
    // SignalProtocolAddress) fails with UnsatisfiedLinkError /
    // NoClassDefFoundError on a real device or emulator, regardless of ABI --
    // confirmed by decompiling the resolved jar (javap/unzip -l), not
    // assumed. org.signal:libsignal-android:0.86.5 is Signal's own published
    // Android-targeting counterpart at the identical version: its AAR bundles
    // the correct jni/<abi>/libsignal_jni.so for arm64-v8a/armeabi-v7a/x86/
    // x86_64 (AGP-recognized native-lib packaging), and its POM declares a
    // `compile`-scope dependency on the exact same
    // org.signal:libsignal-client:0.86.5 for the shared Java API classes
    // (SignalProtocolStore, SignalProtocolAddress, etc.) -- so this one line
    // transitively supplies both the classes RoomSignalProtocolStore
    // (com.hop.data) compiles against AND a native binary that actually
    // loads on Android, with no duplicate-class risk (libsignal-android's own
    // classes.jar contributes only a couple of Android-specific logging
    // classes, no overlap with libsignal-client's package). :crypto's own
    // build.gradle.kts is untouched and still correctly depends on plain
    // libsignal-client -- its tests run as plain JVM/JUnit processes (never
    // inside an Android runtime), so the desktop-native artifact is exactly
    // right there. See the -Xskip-metadata-version-check note in the
    // kotlinOptions block above for why the matching compiler flag is
    // required alongside this.
    implementation("org.signal:libsignal-android:0.86.5")
    // Satisfies the isCoreLibraryDesugaringEnabled requirement above --
    // AGP-recommended, widely-used desugar_jdk_libs version compatible with
    // AGP 8.5.2 (this repo's pinned Android Gradle Plugin version, see the
    // root mobile/android/build.gradle.kts).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Room persistence layer (com.hop.data) -- pinned to 2.6.1, not the latest
    // Room release: Room 2.8.x requires Kotlin 2.0+, which this repo's
    // centrally-pinned Kotlin 1.9.24 (mobile/android/build.gradle.kts) doesn't
    // satisfy. 2.6.1 is confirmed compatible with Kotlin 1.9.23/1.9.24 plus the
    // KSP 1.9.24-1.0.20 build declared in the root build.gradle.kts -- do not
    // bump either version independently of the other.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    // kotlin.test assertion helpers (assertEquals/assertContentEquals/etc.),
    // matching the pattern already used in crypto/build.gradle.kts's test deps.
    androidTestImplementation(kotlin("test"))

    // Jetpack Compose -- app shell (HopNavHost, first-run, main placeholder).
    // Media3 ExoPlayer (Feed screen video playback) is a later slice's concern,
    // deliberately not added here -- keep this scoped to what this slice uses.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Explicit (rather than relying on it arriving transitively via DataStore/
    // lifecycle-*-compose) since app-layer code (SettingsRepository, view
    // models) uses Flow/Mutex/coroutine builders directly. Matches the version
    // already pinned in crypto/build.gradle.kts.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Media3 ExoPlayer -- Feed screen video playback (com.hop.app.feed). No
    // media3-ui-compose: not confirmed to exist/be compatible at this Compose
    // Compiler/BOM pairing, so this uses the classic PlayerView wrapped in
    // AndroidView { } instead -- the standard, guaranteed-available pattern.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // JVM (non-instrumented) unit tests -- this app module's first (FeedViewModel).
    // JUnit4 + kotlin.test, matching the style already used in androidTest/ (not
    // JUnit5, unlike protocol/crypto -- those are plain-Kotlin JVM modules with no
    // androidx.test/AGP unit-test convention to match; this module's existing
    // androidTest/ suite is already JUnit4-based, so JVM unit tests here follow
    // the same convention rather than introducing a second test framework).
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    // Needed to bridge ViewModel's `viewModelScope` (Dispatchers.Main.immediate)
    // onto a deterministic test dispatcher in a plain JVM unit test, where no
    // real Android Main-thread Looper exists.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
