import cn.stbu.config.AppConfig
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeys = Properties().apply {
    val localFile = rootProject.file("keystore.properties")
    if (localFile.isFile) localFile.inputStream().use { load(it) }
}
val releaseSigningReady =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !releaseKeys.getProperty(it).isNullOrBlank() }

android {
    namespace = "cn.stbu.helper"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "cn.stbu.helper"
        minSdk = 26
        targetSdk = 35
        versionCode = AppConfig.VERSION_CODE
        versionName = AppConfig.VERSION_NAME
        manifestPlaceholders["appName"] = AppConfig.APP_NAME
        manifestPlaceholders["appIcon"] = AppConfig.ICON_RESOURCE
    }
    buildTypes {
        getByName("debug") { isDebuggable = true }
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.create("localRelease") {
                storeFile = rootProject.file(releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    jvmToolchain(17)
}

val requireReleaseSigning by tasks.registering {
    doLast {
        check(releaseSigningReady) { "请先将 keystore.properties.example 复制为 keystore.properties 并填写发布签名信息。" }
        check(rootProject.file(releaseKeys.getProperty("storeFile")).isFile) { "发布密钥文件不存在，请检查 keystore.properties 的 storeFile。" }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(requireReleaseSigning) }

// Keep APK file names valid on Windows when the app name contains punctuation.
val apkBaseName = "${AppConfig.APP_NAME}-${AppConfig.VERSION_NAME}"
    .replace(Regex("""[\\/:*?"<>|]"""), "_")

tasks.register<Copy>("publishDebug") {
    group = "distribution"
    description = "构建调试 APK 并输出到 app/Debug"
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("*.apk"); rename { "${apkBaseName}-debug.apk" } }
    into(layout.projectDirectory.dir("Debug"))
}
tasks.register<Copy>("publishRelease") {
    group = "distribution"
    description = "构建带发布签名的 APK 并输出到 app/Release"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk"); exclude("*-unsigned.apk"); rename { "${apkBaseName}-release.apk" } }
    into(layout.projectDirectory.dir("Release"))
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
}
