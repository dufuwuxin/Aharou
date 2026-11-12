buildscript {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
    dependencies {
        // The pinned R8 also accepts the QuickJS dependency's Kotlin 2.4 metadata.
        classpath("com.android.tools:r8:9.1.57")
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.test") version "8.13.2" apply false
    id("androidx.baselineprofile") version "1.3.4" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.dagger.hilt.android") version "2.58" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
