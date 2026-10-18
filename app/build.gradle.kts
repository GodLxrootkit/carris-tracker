import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

/**
 * Signing material, from a file locally and from the environment in CI.
 *
 * The weekly release workflow cannot have a `keystore.properties` checked out - that is the whole
 * reason it lives outside the repository - so it passes KEYSTORE_B64 and the passwords as secrets
 * and the key is materialised under `build/` here. Neither path writes the key or the passwords
 * into the source tree.
 */
val keystoreB64 = System.getenv("KEYSTORE_B64")
val signingFromEnv = !keystoreB64.isNullOrBlank()
val signingAvailable = keystorePropsFile.exists() || signingFromEnv

/** Under `build/`, which is gitignored and wiped on every clean. */
val ciKeystore = rootProject.layout.buildDirectory.file("ci-release.jks").get().asFile

if (signingFromEnv && !ciKeystore.exists()) {
    ciKeystore.parentFile.mkdirs()
    ciKeystore.writeBytes(Base64.getDecoder().decode(keystoreB64!!.trim()))
}

/**
 * Environment wins over the properties file, so CI needs no file at all.
 *
 * `storePassword` maps to `STORE_PASSWORD`, not `STOREPASSWORD` - an upper-case conversion loses
 * the word boundary and CI then builds an APK with no signing config at all. This silently passed
 * local testing because `keystore.properties` was there to fall back on, and only failed in the
 * runner where there is no such file.
 */
fun envNameFor(key: String): String = buildString {
    for ((i, c) in key.withIndex()) {
        if (c.isUpperCase() && i > 0) append('_')
        append(c.uppercaseChar())
    }
}

fun signingValue(key: String): String? =
    System.getenv(envNameFor(key)) ?: keystoreProps.getProperty(key)

android {
    namespace = "pt.carrismetropolitana.tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "pt.carrismetropolitana.tracker"
        minSdk = 24
        targetSdk = 35
    versionCode = 23
    versionName = "2.5.0"
    }

    signingConfigs {
        if (signingAvailable) {
            create("release") {
                storeFile = if (signingFromEnv) ciKeystore
                else rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only attached when a key is actually available, so a checkout with neither source
            // still produces an (unsigned) release build rather than failing outright.
            if (signingAvailable) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")

    // Robolectric gives the map tab real Compose layout on the JVM, so measure/arrange bugs
    // are caught by `./gradlew test` instead of on a device. It was the missing piece: 1.3.0
    // shipped a layout bug that all 34 unit tests passed straight through.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

android {
    testOptions {
        unitTests {
            // Required for Robolectric to inflate resources and resolve R.raw / R.string.
            isIncludeAndroidResources = true
        }
    }
}

/**
 * Robolectric needs a glibc JVM. Termux is Android userland (bionic), so it cannot load the
 * linux-aarch_64 Conscrypt .so Robolectric asks for and every Robolectric test dies with
 * UnsatisfiedLinkError before any of its code runs.
 *
 * Those layout tests are the ones that would have caught the 1.3.0 map bug, so they are not
 * dropped — they are skipped here and run in CI on ubuntu-latest instead.
 */
val runningOnTermux: Boolean =
    System.getenv("PREFIX")?.contains("com.termux") == true

tasks.withType<Test>().configureEach {
    if (runningOnTermux) {
        filter { excludeTestsMatching("*MapLayoutTest*") }
        logger.lifecycle(
            "Skipping Robolectric layout tests: unsupported on Termux (bionic JVM). CI runs them."
        )
    }
}

val apkOutDir = layout.buildDirectory.dir("outputs/apk").get().asFile
val repoRoot = rootProject.projectDir

tasks.register("copyApkToRoot") {
    group = "build"
    description = "Copies built APKs to the repository root as carris-tracker-<variant>.apk"
    doLast {
        apkOutDir.walkTopDown()
            .filter { it.isFile && it.extension == "apk" }
            .forEach { apk ->
                val variant = if (apk.name.contains("release")) "release" else "debug"
                val target = repoRoot.resolve("carris-tracker-$variant.apk")
                apk.copyTo(target, overwrite = true)
                logger.lifecycle("APK: ${target.absolutePath}")
            }
    }
}

tasks.matching { it.name.startsWith("assemble") }.configureEach {
    finalizedBy("copyApkToRoot")
}
