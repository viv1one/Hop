plugins {
    // Version is declared once, apply false, in mobile/android/build.gradle.kts
    // (the root project of this Gradle build) — see the comment there.
    id("org.jetbrains.kotlin.jvm")
}

// Repositories are declared centrally in mobile/android/settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS) since :preseed is
// included as a subproject of that build — do not add a repositories{} block here.

// tools/preseed/ is BUILD_PLAN.md Phase 4's internet-mode pre-seeding
// tooling (memo §8 Phase 2 GTM) -- a standalone, operator-run JVM process,
// same posture as tools/relay-node/'s RelayNode (see that module's own
// build.gradle.kts comment): plain Kotlin JVM, not Android, run by whoever
// is seeding a venue/city, never something HOP itself operates as a hosted
// service on users' behalf (see PreseedNode.kt's own class doc for why this
// still respects the "no HOP server in the content/discovery/delivery path"
// non-negotiable).
//
// Unlike tools/relay-node/, which must NEVER depend on :protocol (its whole
// point is being structurally incapable of reading content -- see that
// module's own build.gradle.kts comment), this module's entire job is
// packaging real content into the exact wire shape the app's real posting
// path produces, so it depends on :protocol AND :crypto (EncryptedFrameCodec/
// ReachTierKeyDistribution live in :protocol and call into :crypto's
// ContentEncryption/DecayKeyStore -- see EncryptedFrameCodec.kt's own doc for
// why that one-way protocol/ -> crypto/ dependency is the ADR 0001-sanctioned
// shape), plus :dht (Kademlia DHT participation), :topics (geohash-prefix
// topic publish -- the protocol/dht bridge, reused rather than duplicated;
// see TopicSubscription.kt's own doc for why that bridge can't live in
// either :protocol or :dht directly), and :p2p (PeerListener/PeerChannel --
// the dht/protocol bridge for accepting and speaking to real inbound
// connections, same reasoning as :topics).
dependencies {
    implementation(project(":protocol"))
    implementation(project(":crypto"))
    implementation(project(":dht"))
    implementation(project(":topics"))
    implementation(project(":p2p"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(project(":rendezvous"))
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

// PreseedCli's real main() entry point, so an operator can run this tool as
// `./gradlew :preseed:run --args=...` without hand-assembling a classpath.
// Matches BUILD_PLAN.md's framing of this module as operator-run tooling,
// not a library consumed by other modules.
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the pre-seeding CLI (com.hop.preseed.PreseedCli)."
    mainClass.set("com.hop.preseed.PreseedCliKt")
    classpath = sourceSets["main"].runtimeClasspath
}
