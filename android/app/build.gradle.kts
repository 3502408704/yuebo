import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val baiduPanAppId = localProperties.getProperty("BAIDU_PAN_APP_ID").orEmpty()
val baiduPanApiKey = localProperties.getProperty("BAIDU_PAN_API_KEY").orEmpty()
val baiduPanSecretKey = localProperties.getProperty("BAIDU_PAN_SECRET_KEY").orEmpty()
val baiduPanSignKey = localProperties.getProperty("BAIDU_PAN_SIGN_KEY").orEmpty()
// 百度开放平台登记的授权码模式回调地址；为空时月播云登录回退设备码流程。
val baiduPanRedirectUri = localProperties.getProperty("BAIDU_PAN_REDIRECT_URI").orEmpty()
val media3Version = "1.11.0"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-ui-compose:$media3Version")
    // Chromecast 投送（2026-09-28 第一梯队 2）：CastPlayer + 设备发现/会话框架
    implementation("androidx.media3:media3-cast:$media3Version")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    // 显式覆盖 ui-graphics 传递依赖的 graphics-path 1.0.0：1.0.0 的 .so 未做 16 KB 页对齐
    implementation("androidx.graphics:graphics-path:1.1.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    // 插件音源（MusicFree 插件协议）的 QuickJS 运行时：与 MusicFree 官方安卓端同引擎
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("net.jthink:jaudiotagger:3.0.1")
implementation("com.google.zxing:core:3.5.3")
testImplementation(kotlin("test-junit"))
    testImplementation("org.json:json:20180813")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val releaseSigningReady = listOf("keyAlias", "keyPassword", "storeFile", "storePassword")
    .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.example.local_music_player"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        testInstrumentationRunner = "com.example.local_music_player.PlaybackEngineInstrumentation"
        // 1.0 起包名对齐「月播」品牌（旧包 com.example.local_music_player 的用户需手动安装新版并重新登录）；
        // 代码包/namespace 仍为 com.example.local_music_player，JNI 与 R 类不受影响。
        applicationId = "com.yuebo.player"
        minSdk = 24
        targetSdk = 36
        buildConfigField("String", "BAIDU_PAN_APP_ID", "\"$baiduPanAppId\"")
        buildConfigField("String", "BAIDU_PAN_API_KEY", "\"$baiduPanApiKey\"")
        buildConfigField("String", "BAIDU_PAN_SECRET_KEY", "\"$baiduPanSecretKey\"")
        buildConfigField("String", "BAIDU_PAN_SIGN_KEY", "\"${baiduPanSignKey}\"")
        buildConfigField("String", "BAIDU_PAN_REDIRECT_URI", "\"$baiduPanRedirectUri\"")
versionCode = 20261005
versionName = "20261005final"
        ndk {
            // 默认（正式发布）只含真机 arm64-v8a；模拟器 QA 用的 x86_64 由 debug 构建类型补充。
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // debug 构建与正式版同签名（调试密钥库），后缀隔离避免覆盖安装正式版
            applicationIdSuffix = ".debug"
            if (providers.gradleProperty("playbackQa").orNull == "true") {
                applicationIdSuffix = ".playbackqa"
            }
            ndk {
                abiFilters += listOf("x86_64")
            }
        }
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.create("release") {
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                    storeFile = file(keystoreProperties.getProperty("storeFile"))
                    storePassword = keystoreProperties.getProperty("storePassword")
                }
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("../build/player-fixtures"))

    packaging {
        jniLibs {
            excludes += setOf(
                "**/armeabi-v7a/**",
                "**/x86/**",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
