// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.apriltagfbt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.apriltagfbt"
        minSdk      = 24
        targetSdk    = 34
        versionCode  = 1
        versionName  = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // 只打包需要的 ABI，减小 APK 体积
    ndk {
        abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // OpenCV Android SDK（如果用 Java API 就需要，JNI 层已静态链入）
    // implementation(fileTree(mapOf("dir" to "../opncvsdk/java", "include" to listOf("*.jar"))))
}
