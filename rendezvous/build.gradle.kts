plugins {
    // Version is declared once, apply false, in mobile/android/build.gradle.kts
    // (the root project of this Gradle build) — see the comment there.
    id("org.jetbrains.kotlin.jvm")
}

// Repositories are declared centrally in mobile/android/settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS) since :rendezvous is
// included as a subproject of that build — do not add a repositories{} block here.

// rendezvous/ implements ADR 0002's narrow bootstrap/rendezvous-node carve-out
// (docs/adr/0002-bootstrap-node-carveout.md): an address-only peer registry
// that answers the ordinary Kademlia PING/FIND_NODE RPCs over dht/'s existing
// DhtUdpTransport, reused as-is -- never forked or reimplemented, see
// RendezvousNode's own class doc for why. That's why this module depends on
// :dht: only for that shared wire-transport/Contact/NodeId/PeerAddress
// plumbing, never for DhtStore or FindValueOutcome.Holders, neither of which
// this module may ever import (see RendezvousNode's class doc). Plain-JVM,
// same portability posture as dht/build.gradle.kts's own comment: works for a
// future non-Android relay/bootstrap process too.
dependencies {
    implementation(project(":dht"))

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // kotlin.test assertion helpers (assertEquals/assertFailsWith/etc.), wired to
    // run on the JUnit 5 platform declared above.
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Only needed for tests: driving DhtUdpTransport's suspend ping/findNode/
    // store/findValue functions as a test "peer" against a running
    // RendezvousNode requires runBlocking. Not an "implementation" dependency
    // of this module's own main source, which never calls a suspend function
    // (RendezvousNode only ever wires plain, non-suspend callbacks).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
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
