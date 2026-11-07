import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.wawek.stickfix"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.wawek.stickfix"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The daemon must exist as a real file in nativeLibraryDir so the root
    // script can exec it, so keep native libs extracted.
    packaging { jniLibs { useLegacyPackaging = true } }

    sourceSets["main"].jniLibs.srcDir("src/main/jniLibsGen")
}

// stickfixd is a standalone executable, not a JNI library. It is built with the
// NDK's clang directly and packaged as lib/arm64-v8a/libstickfixd.so, which is
// the only way to ship an executable inside an APK.
val buildDaemon by tasks.registering(Exec::class) {
    val src = file("src/main/cpp/stickfixd.c")
    val out = file("src/main/jniLibsGen/arm64-v8a/libstickfixd.so")
    inputs.file(src)
    outputs.file(out)
    val props = Properties().apply { rootProject.file("local.properties").inputStream().use { load(it) } }
    val ndkDir = File(props.getProperty("sdk.dir"), "ndk/28.2.13676358")
    val host = if (System.getProperty("os.name").startsWith("Windows")) "windows-x86_64" else "linux-x86_64"
    val clang = File(ndkDir, "toolchains/llvm/prebuilt/$host/bin/clang" + if (host.startsWith("windows")) ".exe" else "")
    doFirst { out.parentFile.mkdirs() }
    commandLine(
        clang.path, "--target=aarch64-linux-android29", "-O2", "-Wall", "-Wno-unused-result",
        "-fPIE", "-pie", "-s", "-o", out.path, src.path, "-lm"
    )
}
tasks.named("preBuild") { dependsOn(buildDaemon) }
