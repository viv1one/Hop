plugins {
    // Version is declared once, apply false, in mobile/android/build.gradle.kts
    // (the root project of this Gradle build) — see the comment there.
    id("org.jetbrains.kotlin.jvm")
}

// Repositories are declared centrally in mobile/android/settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS) since :topics is
// included as a subproject of that build — do not add a repositories{} block here.

// Phase 4 Slice 6: the geohash-prefix-topic-subscription bridge between
// :protocol (ReachTierGeohash/Geohash -- tier-to-precision mapping, target
// cell + neighbor cells) and :dht (NodeId/DhtNode -- the Kademlia
// announce/get-peers primitive). This module exists ONLY because :protocol
// and :dht may never depend on each other in either direction (dht/'s own
// NodeId.kt doc; :protocol's ReachTierGeohash doc) -- see this module's own
// TopicSubscription.kt for the full reasoning behind putting the bridge
// here rather than in :app or either of the two modules it connects.
dependencies {
    implementation(project(":protocol"))
    implementation(project(":dht"))
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
