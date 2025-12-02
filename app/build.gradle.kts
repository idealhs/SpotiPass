plugins {
    id("com.android.application") version "8.2.2"
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // 仅编译期依赖，打包时不能把 Xposed API 放进 APK，否则 NPatch/LSPosed 会拒绝加载模块
    compileOnly("de.robv.android.xposed:api:82")
}

