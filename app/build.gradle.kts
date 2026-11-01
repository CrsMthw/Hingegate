// `Properties` must be IMPORTED: inside a build script `java` resolves to Gradle's JavaPluginExtension accessor,
// so a fully-qualified `java.util.Properties` fails with "Unresolved reference 'util'".
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ─── Release signing ─────────────────────────────────────────────────────────
// Credentials live in local.properties (gitignored): KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
// They are read at BUILD time only; what ships in the APK is the signature, never the secret — so no
// buildConfigField may ever expose any of them.
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Null on a fresh clone (or in CI) with no local.properties or no keystore: no signing config is registered
// and the release build stays unsigned instead of failing.
val keystoreFile = keystoreProps.getProperty("KEYSTORE_PATH")
    ?.let { file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.crsmthw.hingegate"
    // The alpha BOM's Compose 1.13 refuses to build against platform 37.0; 37.1 or later is required, and AGP
    // installs platform 37.2 on its own when it is missing.
    compileSdk      = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.crsmthw.hingegate"
        // Android 12: every foldable still in use runs it or later, and Engine.getDisplayContext (API 30) and the
        // untrusted-touch opacity limit (API 31) are both there.
        minSdk        = 31
        targetSdk     = 37
        versionCode   = 2
        versionName   = "1.0.0"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile     = keystoreFile
                storePassword = keystoreProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias      = keystoreProps.getProperty("KEY_ALIAS")
                keyPassword   = keystoreProps.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // findByName, not getByName: null when unconfigured, which leaves the APK unsigned instead of failing
            // the build with "SigningConfig 'release' not found".
            signingConfig     = signingConfigs.findByName("release")
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose     = true
        // VERSION_NAME for the About section; no secret is ever a buildConfigField (see the signing note above).
        buildConfig = true
    }
}

kotlin { jvmToolchain(21) }

// JVM unit tests only. Device tests stay disabled: their component wiring trips Gradle's "Project object as a
// dependency notation" deprecation from inside AGP, and the project has no instrumented tests by policy.
androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.deviceTests.forEach { (_, deviceTest) -> deviceTest.enable = false }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // LifecycleService: the overlay's foreground service collects flows in its own lifecycleScope
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    // Compose BOM + UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Fold posture and hinge geometry
    implementation(libs.androidx.window)

    // Settings, the active crack and the detection log
    implementation(libs.androidx.datastore.preferences)

    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Unit tests (JVM only)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
