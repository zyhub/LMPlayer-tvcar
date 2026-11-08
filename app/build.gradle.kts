import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 绛惧悕瀵嗛挜涓庡瘑鐮?*涓嶅叆搴?*锛氫紭鍏堣椤圭洰涓凡蹇界暐鐨?local.properties锛屽叾娆¤鍚屽悕鐜鍙橀噺銆?
// 涓よ€呴兘鍙栦笉鍒帮紙渚嬪浠?GitHub 鍏嬮殕鍚庣紪璇戯級鏃朵笉鍒涘缓 release 绛惧悕閰嶇疆 鈥斺€?鏋勫缓渚濈劧鎴愬姛锛?
// 鍙槸浜у嚭鏈鍚?APK锛岃€屾簮鐮佹爲閲屼笉鍚换浣曞彛浠ゃ€傚垏鍕挎妸瀵嗙爜鍐欏洖杩欎釜鏂囦欢銆?
// S-01：签名文件统一放在上一级「项目根目录」（与手机端共用一个仓库根）：
//   LMPlayerTV-release.jks + LMPlayerTV-signing.properties
// 根目录没有时回落到本工程旧位置（signing.properties / app/lmplayer-tv-release.jks），兼容旧编译环境。
val signingRoot: File = rootProject.projectDir.parentFile
val rootSigningProps = signingRoot.resolve("LMPlayerTV-signing.properties")
val rootStoreFile = signingRoot.resolve("LMPlayerTV-release.jks")
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    val sf = rootSigningProps.takeIf { it.exists() } ?: rootProject.file("signing.properties")
    if (sf.exists()) sf.inputStream().use { load(it) }
}
fun signingSecret(propKey: String, envKey: String): String? =
    (signingProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

// 根目录的 jks 优先；否则按 lmplayer.storeFile（相对工程根目录解析）或 app/lmplayer-tv-release.jks。
val releaseStoreFile = rootStoreFile.takeIf { it.exists() }
    ?: signingSecret("lmplayer.storeFile", "LMPLAYER_STORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }
    ?: file("lmplayer-tv-release.jks").takeIf { it.exists() }
val releaseStorePassword = signingSecret("lmplayer.storePassword", "LMPLAYER_STORE_PASSWORD")
val releaseKeyAlias = signingSecret("lmplayer.keyAlias", "LMPLAYER_KEY_ALIAS")
val releaseKeyPassword = signingSecret("lmplayer.keyPassword", "LMPLAYER_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.lm.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lm.player.tv"
        minSdk = 23
        targetSdk = 34
        // 版本号唯一来源：代码中通过 BuildConfig.VERSION_NAME / VERSION_CODE 读取，禁止再在 Kotlin 里硬编码
        // 【版本号已锁定】按维护者要求固定为 1.10.32 (79)，编译出包时不要自动递增；如需变更请先征得维护者同意
        versionCode = 79
        versionName = "1.10.32"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // findByName 鑰岄潪 getByName锛氭病鏈夋湰鍦板瘑閽ユ椂杩斿洖 null锛屼骇鍑烘湭绛惧悕 APK 鑰屼笉鏄鏋勫缓澶辫触
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // S-04：debug 包使用 Android 默认 debug 签名，不再借用 release 私钥
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    buildFeatures {
        compose = true
        // 生成 BuildConfig.VERSION_NAME / VERSION_CODE，AppUpdateManager 由此读取版本号，避免与 defaultConfig 不一致
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/com/android/build/gradle/*"
            excludes += "/META-INF/version-control-info.textproto"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.04.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // ?????? (QuickJS Kotlin 1.9.x ???)
    implementation("io.github.dokar3:quickjs-kt-android:1.0.0-alpha02")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
