plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Also used (without a version) by the plain-Kotlin :protocol module — declared
    // here, apply false, so its version is resolved once for the whole build instead
    // of conflicting with the version already on the root build's plugin classpath.
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
    // Room's annotation processor, for :app's Room persistence layer (com.hop.data).
    // Pinned to the exact KSP build for Kotlin 1.9.24 -- do not bump independently
    // of the central Kotlin plugin version above.
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
