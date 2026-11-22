plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val robolectricSdk by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val prepareTestSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("verified-test-sdk"))
}

check(listOf("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD")
    .none { !System.getenv(it).isNullOrBlank() }) {
    "Signing credentials must not be passed to Gradle. Use the isolated signing step."
}

android {
    namespace = "io.github.sasakiy84.qarry"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.sasakiy84.qarry"
        minSdk = 36
        targetSdk = 36
        versionCode = providers.gradleProperty("releaseVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()
    }

    signingConfigs.getByName("debug") {
        System.getenv("ANDROID_USER_HOME")?.let { storeFile = file("$it/debug.keystore") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(prepareTestSdk)
            // Isolate Compose's main dispatcher between Robolectric test classes.
            it.forkEvery = 1
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("verified-test-sdk").get().asFile)
        }
    }
}

tasks.configureEach {
    if (name == "packageRelease" || name == "packageReleaseBundle") {
        val unsignedRelease = providers.gradleProperty("unsignedRelease").orElse("false")
        inputs.property("unsignedRelease", unsignedRelease)
        doFirst {
            check(unsignedRelease.get().toBoolean()) {
                "Use -PunsignedRelease=true, then sign outside Gradle."
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    robolectricSdk("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
}
