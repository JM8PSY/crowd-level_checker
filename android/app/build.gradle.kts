import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// local.properties（git 管理外）から Ambient・Supabase・ノードの既定値を読む。未設定なら空文字
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// -PNODE_ID=... のように Gradle プロパティで渡した値を優先する（端末ごとに local.properties を書き換えずにビルドするため）
fun localProp(key: String, default: String = ""): String =
    providers.gradleProperty(key).orNull ?: localProps.getProperty(key, default)

android {
    namespace = "com.crowdlevel.node"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.crowdlevel.node"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "AMBIENT_CHANNEL_ID", "\"${localProp("AMBIENT_CHANNEL_ID")}\"")
        buildConfigField("String", "AMBIENT_WRITE_KEY", "\"${localProp("AMBIENT_WRITE_KEY")}\"")
        // 送信先の Supabase。publishable key は公開してよいキーなので、既定値をここに書いておく（web/config.js と同じ）
        buildConfigField("String", "SUPABASE_URL", "\"${localProp("SUPABASE_URL", "https://vxhbjnbsrfopczqxqbhp.supabase.co")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${localProp("SUPABASE_KEY", "sb_publishable_w5olGvZMCO96K-nPwrZ6cQ_koLNE93d")}\"")
        // ノード ID とトークンは端末ごとに違う。local.properties に書けば、その値を既定値にしてビルドできる
        buildConfigField("String", "NODE_ID", "\"${localProp("NODE_ID", "node-01")}\"")
        buildConfigField("String", "NODE_TOKEN", "\"${localProp("NODE_TOKEN")}\"")
    }

    buildTypes {
        debug {
            // 開発中はローカルの HTTP サーバへ送れるように平文通信を許可する（Phase 2 で使用）
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main").kotlin.srcDirs("src/main/kotlin")
        getByName("test").kotlin.srcDirs("src/test/kotlin")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
