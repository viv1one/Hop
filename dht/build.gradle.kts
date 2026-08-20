plugins {
    // Version is declared once, apply false, in mobile/android/build.gradle.kts
    // (the root project of this Gradle build) — see the comment there.
    id("org.jetbrains.kotlin.jvm")
}

// Repositories are declared centrally in mobile/android/settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS) since :dht is
// included as a subproject of that build — do not add a repositories{} block here.

// Phase 4 Slice 3: real network-facing DHT code (PING/PONG liveness RPC over
// UDP), so this module now has its first real dependency, kotlinx-coroutines-
// core (matching crypto/build.gradle.kts's exact coordinates -- the same
// portability posture crypto/ already established: plain-JVM, works for a
// future non-Android relay/bootstrap process too). Still deliberately zero
// dependency on :protocol or :crypto in either direction -- see
// dht/src/main/kotlin/com/hop/dht/NodeId.kt's doc comment for why NodeId's
// 32-byte size is a hardcoded literal rather than a dependency on
// protocol's Frame.CLIP_HASH_SIZE.
dependencies {
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
}
