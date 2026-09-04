import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    namespace = "tw.cornming.vlogstitch"
    compileSdk = 35

    /*
     * 固定的簽章金鑰。
     *
     * 原本用 debug 簽章，但 debug 金鑰是建置機器自動產生的，而 GitHub Actions
     * 的 runner 每次都是全新機器，等於每次建置都換一把金鑰。Android 不允許用
     * 不同簽章的 APK 覆蓋安裝，更新就會失敗並顯示「未安裝應用程式」。
     *
     * 預設用 repo 裡的金鑰檔；設了 RELEASE_KEYSTORE_PATH 環境變數就改用那一把，
     * 方便日後改成 GitHub secret 而不必動程式。
     */
    signingConfigs {
        create("release") {
            // 注意：workflow 傳未設定的 secret 會得到空字串而不是 null，
            // 所以要用 isNullOrBlank 判斷，只用 ?: 會拿到空密碼而建置失敗。
            fun env(name: String, fallback: String): String {
                val v = System.getenv(name)
                return if (v.isNullOrBlank()) fallback else v
            }
            val envPath = System.getenv("RELEASE_KEYSTORE_PATH")
            storeFile = if (envPath.isNullOrBlank())
                rootProject.file("keystore/vlog-stitch.jks") else file(envPath)
            storePassword = env("RELEASE_STORE_PASSWORD", "vlogstitch")
            keyAlias = env("RELEASE_KEY_ALIAS", "vlogstitch")
            keyPassword = env("RELEASE_KEY_PASSWORD", "vlogstitch")
        }
    }

    defaultConfig {
        applicationId = "tw.cornming.vlogstitch"
        minSdk = 29
        targetSdk = 35
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        // 更新檢查要知道去哪裡問
        buildConfigField("String", "REPO", "\"cornming/vlog-stitch\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 影片處理：走 Android 原生 MediaCodec，不是 ffmpeg
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
}
