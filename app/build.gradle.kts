import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI'da her derleme bir öncekinin üzerine kurulabilsin diye sürüm numarası
// GitHub Actions çalıştırma numarasından gelir.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.efkstudio.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.efkstudio.music"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"skypro50/Deneme\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("efk-release.jks")
            storePassword = System.getenv("EFK_STORE_PASSWORD") ?: "efkstudio"
            keyAlias = System.getenv("EFK_KEY_ALIAS") ?: "efk"
            keyPassword = System.getenv("EFK_KEY_PASSWORD") ?: "efkstudio"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        // youtubedl-android, Python/ffmpeg/QuickJS ikili dosyalarını
        // nativeLibraryDir içinden çalıştırır; bu yüzden çıkarılmış olmalılar.
        jniLibs {
            useLegacyPackaging = true
            // Telefonlar ARM işlemci kullanır; x86 kütüphaneleri APK'yı boşuna büyütür.
            excludes += setOf("lib/x86/**", "lib/x86_64/**")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
