plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-parcelize")
    kotlin("kapt")
}

android {
    signingConfigs {
        create("release") {
            storeFile =
                file("/Users/shebleredwan/Desktop/Work/Network Measurement SDKs/network-measurement-unified-sdk/app/key/unified_sdk_host_app.jks")
            storePassword = "SDK123456"
            keyAlias = "unified_host_app"
            keyPassword = "SDK123456"
        }
    }
    namespace = "com.ptsl.networksdk_event"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ptsl.networksdk_event"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources=true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources=false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true

    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.hilt.work)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(project(mapOf("path" to ":network-sdk")))
//    implementation("com.github.primetechsoltutions:network-measurement-unified-sdk:Test-1.1.0")



    implementation(libs.play.services.location)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")



}