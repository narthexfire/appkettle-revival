import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

// Release signing is OPTIONAL and lives outside the repository. Nothing secret is committed.
// Either create android-app/keystore.properties (git-ignored) or pass -PkettleSigning=<path>.
// The file holds: storeFile, storePassword, keyAlias, keyPassword. See android-app/README.md.
// Without it, `assembleRelease` produces an unsigned APK and `assembleDebug` a debug-signed one.
val signingFile = file(
    (project.findProperty("kettleSigning") as String?)
        ?: rootProject.file("keystore.properties").path
)
val signingProps = Properties().apply { if (signingFile.exists()) FileInputStream(signingFile).use { load(it) } }

android {
    namespace = "io.github.narthexfire.kettle"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.narthexfire.kettle"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.testLogging { events("passed", "skipped", "failed", "standardOut") }
            }
        }
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.46.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.46.1")
}

// Compose UI tests (screenshots, long-press, editor) need the debug-only Compose test activity;
// run them on the debug variant only.
tasks.withType<Test>().configureEach {
    if (name.contains("Release")) filter {
        excludeTestsMatching("*ScreenshotTest")
        excludeTestsMatching("*PresetLongPressTest")
        excludeTestsMatching("*CrashLogTest")
        excludeTestsMatching("*UpgradedRangeTest")
        excludeTestsMatching("*SheetRangeTest")
        excludeTestsMatching("*RepeatButtonTest")
    }
}
