import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class PrepareQuoteModel : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Input abstract val modelUrl: Property<String>
    @get:Input abstract val modelSha256: Property<String>
    @get:Internal abstract val offline: Property<Boolean>

    @TaskAction
    fun prepare() {
        val model = outputDirectory.file("quote/model.onnx").get().asFile
        val expectedBytes = 26_400_965
        fun matches(bytes: ByteArray): Boolean =
            bytes.size == expectedBytes &&
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) == modelSha256.get()

        if (model.isFile && model.length() == expectedBytes.toLong() && matches(model.readBytes())) return
        check(!offline.get()) { "The verified quote model is not cached. Run :app:prepareQuoteModel once online." }
        val connection = URI(modelUrl.get()).toURL().openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 40_000
        }
        val bytes = connection.getInputStream().use { it.readNBytes(expectedBytes + 1) }
        check(matches(bytes)) { "The downloaded quote model failed SHA-256 verification." }
        model.parentFile.mkdirs()
        model.writeBytes(bytes)
    }
}

val prepareQuoteModel by tasks.registering(PrepareQuoteModel::class) {
    outputDirectory.set(layout.buildDirectory.dir("generated/quoteAssets"))
    modelUrl.set(
        "https://huggingface.co/hotchpotch/bekko-static-embedding-v1/resolve/c18167b8f244c1bc6de21ce9b710bac8a29c4615/onnx/model_int8_dim128.onnx",
    )
    modelSha256.set("b848886b593c3b00e99815482ac1e963f93f194ed087390c2ee769d14206e1ab")
    offline.set(gradle.startParameter.isOffline)
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareQuoteModel, PrepareQuoteModel::outputDirectory)
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
            proguardFiles("proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }
    androidResources { noCompress += "onnx" }

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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    robolectricSdk("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
}
