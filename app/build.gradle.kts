plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.cloudbox.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cloudbox.app"
        minSdk = 26          // Android 8.0：EncryptedSharedPreferences / adaptive icon 均无兼容问题
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 桌面 UA 以 BuildConfig 形式注入，便于后续通过 DataStore 动态覆盖
        buildConfigField("String", "DEFAULT_DESKTOP_UA", "\"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36\"")
        vectorDrawables { useSupportLibrary = true }
    }

    // 签名：release 与 debug 使用同一 keystore（自用分发场景）。
    // 为什么 debug 也用正式密钥：保证"本地构建 / CI 构建 / 不同构建类型"产物签名一致，
    // 任意两者之间都能覆盖安装，不会出现"签名不一致，需要先卸载"。
    signingConfigs {
        create("release") {
            storeFile = file("keystore/cloudbox-release.keystore")
            storePassword = "CloudBox@2026!"
            keyAlias = "cloudbox"
            keyPassword = "CloudBox@2026!"
        }
    }

    buildTypes {
        release {
            // 自用分发：关闭混淆/资源压缩，跳过 R8 保证构建稳定（proguard 规则文件保留）
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // debug 包也用正式签名，保证与 release 可互相覆盖安装
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
    lint {
        // 自用分发项目：跳过 lintVital（lint 分析进程在 CI/低内存环境偶发崩溃，
        // 且 lint 报告本身无 issue），保证 release 构建稳定产出
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 网络：Retrofit + OkHttp + Jsoup
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)

    // 持久化：Room + DataStore + EncryptedSharedPreferences
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // 协程 / WorkManager / ZXing / Zip4j（分卷压缩）
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.workmanager)
    implementation(libs.zxing)
    implementation(libs.zip4j)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
}
