plugins {
    // Version is declared once, apply false, in mobile/android/build.gradle.kts
    // (the root project of this Gradle build) — see the comment there.
    id("org.jetbrains.kotlin.jvm")
}

// Repositories are declared centrally in mobile/android/settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS) since :relay-node is
// included as a subproject of that build — do not add a repositories{} block here.

// tools/relay-node/ is BUILD_PLAN.md's volunteer relay-node fallback for the
// symmetric-NAT case (Phase 4 -- see RelayNode.kt's own class doc for the
// full trust-model reasoning). It depends on :dht for NodeId ONLY (reused
// for consistency -- a relay pairs two peers by their existing 32-byte
// NodeId, nothing else from :dht) and MUST NEVER depend on :protocol,
// directly or transitively. That absence is the whole point: it's what
// makes "this module cannot read content" true by construction rather than
// by policy, the same discipline rendezvous/build.gradle.kts's own comment
// already established for that module's narrower "cannot answer
// content/topic queries" guarantee. Do not add a dependency on :protocol
// here -- see RelayNode.kt's class doc, and RelayNodeTest's own
// zero-:protocol-dependency check, before doing so.
//
// Plain-JVM, same portability posture as dht/build.gradle.kts's and
// rendezvous/build.gradle.kts's own comments: this is meant to run as a
// standalone volunteer-operated process, not inside the Android app.
dependencies {
    implementation(project(":dht"))

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
