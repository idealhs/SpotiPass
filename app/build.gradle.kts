plugins {
    id("com.android.application") version "8.2.2"
}

import java.util.Properties
import java.io.FileInputStream

val localProps = Properties()
rootProject.file("local.properties").let { f ->
    if (f.exists()) FileInputStream(f).use { localProps.load(it) }
}

android {
    namespace = "com.spotipass.module"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.spotipass.module"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("RELEASE_STORE_FILE", ""))
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS", "")
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources.excludes += "META-INF/**"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.15.0")

    // 仅编译期依赖，打包时不能把 Xposed API 放进 APK，否则 NPatch/LSPosed 会拒绝加载模块
    compileOnly("de.robv.android.xposed:api:82")
}

