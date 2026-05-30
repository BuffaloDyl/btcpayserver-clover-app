import java.util.Properties

val localProps = Properties()
localProps.load(rootProject.file("local.properties").inputStream())

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.buffalodyl.btcpayservercloverplugin"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.buffalodyl.btcpayservercloverplugin"
        minSdk = 24
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 29
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProps["KEYSTORE_PATH"] as String)
            storePassword = localProps["KEYSTORE_PASSWORD"] as String
            keyAlias = localProps["KEY_ALIAS"] as String
            keyPassword = localProps["KEY_PASSWORD"] as String
        }
    }

    buildTypes {
        debug {
            signingConfig = null
        }
        release {
            isMinifyEnabled = false
            signingConfig = null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation("com.clover.sdk:clover-android-sdk:latest.release")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.google.zxing:core:3.5.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
