plugins {
    // Version is declared once, apply false, in mobile/android/build.gradle.kts
    // (the root project of this Gradle build) — see the comment there.
    id("org.jetbrains.kotlin.jvm")
}

// Repositories are declared centrally in mobile/android/settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS) since :p2p is
// included as a subproject of that build — do not add a repositories{} block here.

// Phase 4's "IPv6-first connection attempts... over the internet-mode DHT"
// step: a direct peer-to-peer socket layer that races an IPv6 candidate
// before an IPv4 one ("Happy Eyeballs", RFC 8305) and exchanges
// WireEnvelope-framed bytes over the resulting socket. This module exists
// ONLY because :dht (PeerAddress -- where to connect) and :protocol
// (WireEnvelope/WirePayloadType -- what's being exchanged) may never depend
// on each other in either direction (dht/'s own NodeId.kt doc; protocol/'s
// ReachTierGeohash doc) -- :topics' TopicSubscription.kt documents the
// identical problem shape for its own DHT-topic bridge, and is the
// precedent this module follows. See this module's own PeerDialer.kt doc
// for the full reasoning. Plain-JVM, same portability posture as
// dht/build.gradle.kts's own comment: works for a future non-Android
// relay/bootstrap process too. Deliberately no kotlinx-coroutines-core
// dependency -- PeerDialer.dial and PeerChannel's send/receive are plain
// blocking calls (java.net.Socket's own connect-timeout parameter already
// bounds the wait), meant to be run on a background thread by callers, the
// same posture com.hop.transport.WifiDirectTransport's Thread-based
// connect/accept/receive loops already establish -- not a suspend-function
// API like :dht/:topics use for their own, differently-shaped, concurrent
// multi-lookup needs.
dependencies {
    implementation(project(":dht"))
    implementation(project(":protocol"))

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
