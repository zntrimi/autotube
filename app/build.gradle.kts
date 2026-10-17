import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.imaizentarou.autotube"
    compileSdk = 37

    defaultConfig {
        // Play Console のアプリ枠に紐づくパッケージ名。コード上の namespace（dev.imaizentarou.autotube）はそのまま。
        applicationId = "com.autotube.zentaro"
        minSdk = 29
        targetSdk = 37
        // Play の内部アプリ共有はアップロードごとに versionCode を上げる必要がある。
        // scripts/release.sh が VERSION_CODE（ビルド時刻の分）を渡す。無ければ 1。
        val versionFromEnv = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionCode = versionFromEnv
        // GitHub のリリースは scripts/github-release.sh が VERSION_NAME（例 0.2.0）を渡す。
        versionName = providers.environmentVariable("VERSION_NAME").orNull ?: "0.1.$versionFromEnv"
    }

    // 署名は debug/release とも ~/.android/personal-release.jks（リポジトリ外）。
    // Play は debug 証明書のビルドを受け付けないので個人鍵を使い、adb で入れるビルドも同じ鍵にして
    // Play 経由のビルドと上書きし合えるようにする。鍵が無ければ従来の debug 鍵で署名する。
    val personalKey = file(System.getProperty("user.home") + "/.android/personal-release.properties")
    if (personalKey.exists()) {
        val properties = Properties().apply { personalKey.inputStream().use { load(it) } }
        signingConfigs.getByName("debug") {
            storeFile = file(properties.getProperty("storeFile"))
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // adb で入れる開発用（DHU）。Play から入れる release と共存できるようパッケージ名を分ける。
            applicationIdSuffix = ".debug"
            // Play 版と並んで入るので、車のランチャーで見分けられる名前にする。
            resValue("string", "app_name", "AutoTube 開発版")
        }
        release {
            // R8 で縮めない（NewPipeExtractor の rhino・jsoup が壊れやすい。個人用なので不要）。
            isMinifyEnabled = false
            resValue("string", "app_name", "AutoTube")
            // 実車の Android Auto は debuggable なアプリを候補から外すので、実車用は release を使う。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        // AppLog がバージョンをログ先頭に書くのに使う。
        buildConfig = true
        compose = true
        // debug と release でアプリ名を分ける（buildTypes の resValue）。
        resValues = true
    }

    packaging {
        resources {
            // rhino / jsoup が同名のメタファイルを持ち込むので重複を無視する。
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.reorderable)

    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
}
