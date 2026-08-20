plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

val keystoreProperties = Properties().apply {
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun readFujiNetRuntimeVersion(): String {
    val versionHeader = rootProject.file("tools/fujinet/work/fujinet-firmware/include/version.h")
    if (!versionHeader.isFile) {
        return "fujinet-runtime-v1"
    }
    val match = Regex("""#define\s+FN_VERSION_FULL\s+"([^"]+)"""")
        .find(versionHeader.readText())
    return match?.groupValues?.get(1) ?: "fujinet-runtime-v1"
}

val fujiNetRuntimeVersion = readFujiNetRuntimeVersion()

// jzIntv core version is recorded by tools/jzintv/build-jzintv-core.sh in a
// .source-info stamp once the core has been staged from the desktop checkout
// (see INTV_DESKTOP_SRC in that script).
fun readJzIntvVersion(): String {
    val stamp = rootProject.file("app/src/main/cpp-generated/intv/.source-info")
    if (stamp.isFile) {
        val text = stamp.readText()
        val jzintv = Regex("""^jzintv_version=(.+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
        val commit = Regex("""^desktop_commit=(.+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()?.take(8)
        return when {
            !jzintv.isNullOrBlank() && !commit.isNullOrBlank() -> "$jzintv (desktop $commit)"
            !jzintv.isNullOrBlank() -> jzintv
            else -> "Unknown"
        }
    }
    return "Unknown"
}

val jzIntvVersion = readJzIntvVersion()

// Optional dev override: -PintvAbi=arm64-v8a (or a comma list, e.g.
// -PintvAbi=arm64-v8a,armeabi-v7a) builds a subset of ABIs for fast iteration.
// Unset => all three packaged ABIs (release/default).
val intvAbis: List<String>? = (project.findProperty("intvAbi") as String?)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
val nativeAbiArgs: List<String> =
    if (intvAbis != null) intvAbis.flatMap { listOf("--abi", it) } else listOf("--all-abis")

// Dev-only flag: -PintvRoms=true stages exec.bin/grom.bin/ecs.bin from the
// desktop checkout's tools/jzintv/roms/ into assets-generated/intv-roms/ so a
// local debug build boots without an import step. NEVER set for a release
// build -- see COMPLIANCE.md and the release-build-type guard below, which
// throws rather than silently shipping ROMs.
val intvRoms: Boolean = (project.findProperty("intvRoms") as String?)?.toBoolean() ?: false

// Stages the toolkit-agnostic Intellivision core (jzIntv + the FujiNet patch +
// intvsession glue) from the local fujinet-go-intv-desktop checkout into
// src/main/cpp-generated/intv/ for CMakeLists.txt to compile. One source of
// truth for the jzIntv tree and jzintv-fujinet.patch -- see the script's own
// header for the INTV_DESKTOP_SRC / JZINTV_SRC overrides.
val prepareJzIntvCore by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Stages the jzIntv + FujiNet-patched Intellivision core from the desktop checkout."
    workingDir = rootProject.projectDir
    commandLine(
        listOf("bash", rootProject.file("tools/jzintv/build-jzintv-core.sh").absolutePath) +
            nativeAbiArgs + (if (intvRoms) listOf("--with-roms") else emptyList())
    )
    inputs.file(rootProject.file("tools/jzintv/build-jzintv-core.sh"))
    inputs.property("intvRoms", intvRoms)
    outputs.dir(project.file("src/main/cpp-generated/intv"))
    if (intvRoms) {
        outputs.dir(project.file("src/main/assets-generated/intv-roms"))
    }
}

// Builds the FujiNet Intellivision Android runtime (libfujinet.so per ABI +
// runtime assets) from the local fujinet-firmware checkout.
val prepareFujiNetRuntime by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Builds the FujiNet Intv Android runtime for the packaged ABIs."
    workingDir = rootProject.projectDir
    commandLine(listOf("bash", rootProject.file("tools/fujinet/build-fujinet.sh").absolutePath) + nativeAbiArgs)
    inputs.file(rootProject.file("tools/fujinet/build-fujinet.sh"))
    inputs.dir(rootProject.file("tools/fujinet/support"))
    outputs.dir(project.file("src/main/assets-generated/fujinet"))
    outputs.dir(project.file("src/main/jniLibs-generated"))
}

// Audits the merged assets + native libs for embedded ROM signatures before a
// release build can be assembled -- makes the "release builds ship no
// copyrighted ROMs" claim in COMPLIANCE.md mechanical rather than aspirational.
val verifyNoEmbeddedRoms by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails if EXEC/GROM/ECS ROM bytes are present in the merged release assets or libs."
    workingDir = rootProject.projectDir
    commandLine(
        "python3", rootProject.file("tools/jzintv/verify-no-roms.py").absolutePath,
        "--require",
        project.file("build/intermediates/assets/release").absolutePath,
        project.file("build/intermediates/merged_native_libs/release").absolutePath
    )
    // The scan targets are merge outputs -- without this ordering the check
    // can run against directories that don't exist yet and prove nothing.
    mustRunAfter(
        tasks.matching { it.name.startsWith("merge") && it.name.contains("Release") }
    )
}

tasks.configureEach {
    if (name.contains("Release") || name == "preBuild") {
        dependsOn(prepareJzIntvCore, prepareFujiNetRuntime)
    }
}

tasks.matching { task ->
    task.name.startsWith("merge") && (
        task.name.endsWith("Assets")
            || task.name.endsWith("JniLibFolders")
            || task.name.endsWith("NativeLibs")
    )
}.configureEach {
    dependsOn(prepareJzIntvCore, prepareFujiNetRuntime)
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyNoEmbeddedRoms)
}

android {
    namespace = "online.fujinet.go.intv"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "online.fujinet.go.intv"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.3"
        buildConfigField("String", "JZINTV_VERSION", "\"${jzIntvVersion}\"")
        buildConfigField("String", "FUJINET_RUNTIME_VERSION", "\"${fujiNetRuntimeVersion}\"")
        buildConfigField("boolean", "DEV_ROMS", intvRoms.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DINTV_WITH_ROMS=${if (intvRoms) "ON" else "OFF"}")
            }
        }
        ndk {
            if (intvAbis != null) {
                abiFilters += intvAbis
            } else {
                // x86 (32-bit) is intentionally excluded, matching every other
                // family member: no real x86-32 devices exist and x86_64
                // covers emulators. arm64-v8a (primary) + armeabi-v7a (legacy
                // ARM) + x86_64 (emulator) cover the live device matrix.
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            ndk { debugSymbolLevel = "FULL" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    if (intvRoms) {
        // -PintvRoms is a dev-only convenience for local debug builds. A
        // release build carrying it would ship Mattel-copyrighted system
        // ROMs -- refuse outright instead of relying on
        // verifyNoEmbeddedRoms to catch it after the fact. Checked against
        // the task graph (not at buildTypes{} configuration time) so this
        // doesn't also trip on unrelated debug-only invocations such as
        // `assembleDebug`, since Gradle configures every build type's DSL
        // block regardless of which task is actually requested.
        gradle.taskGraph.whenReady {
            val releaseTaskRequested = allTasks.any { task ->
                task.path.contains(":app:") && task.name.contains("Release")
            }
            if (releaseTaskRequested) {
                throw GradleException(
                    "-PintvRoms=true is a dev-only flag; refusing a release build with it set. " +
                        "See COMPLIANCE.md."
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    sourceSets {
        getByName("main") {
            // assets-generated/{fujinet,intv-roms} and jniLibs-generated/<abi>/
            // libfujinet.so are produced by the two prepare* tasks above.
            assets.srcDir("src/main/assets-generated")
            jniLibs.srcDir("src/main/jniLibs-generated")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation(libs.junit)
    testImplementation(libs.androidx.lifecycle.runtime.testing)
    testImplementation(libs.androidx.lifecycle.viewmodel.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
