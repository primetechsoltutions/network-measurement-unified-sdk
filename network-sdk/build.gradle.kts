

// TODO:  For JitPack
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("maven-publish")
}

version = project.findProperty("SdkVersion") as String? ?: "1.0.0"

android {
    namespace = "com.ptsl.network_sdk"
    compileSdk = 36
    buildFeatures{
        buildConfig = true
    }

    defaultConfig {
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        val baseURL = project.findProperty("BASE_URL") as String? ?: "BASE_URL"
        buildConfigField("String", "BASE_URL", "\"$baseURL\"")

        val token = project.findProperty("TOKEN") as String? ?: "TOKEN"
        buildConfigField("String", "TOKEN", "\"$token\"")

        val sdkVersion = project.findProperty("SdkVersion") as String? ?: "SdkVersion"
        buildConfigField("String", "SdkVersion", "\"$sdkVersion\"")

    }
    publishing {
        // expose both variants for publishing
        singleVariant("release") {}
        singleVariant("debug") {}
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        freeCompilerArgs += "-Xstring-concat=inline" // Optional: safe string concat for older APIs

    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)



    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Room
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Permissions and location
    implementation(libs.play.services.location)

    // NetMonster
    implementation(libs.core)

    // Networking (Retrofit + OkHttp)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)

    //
    coreLibraryDesugaring(libs.desugar.jdk.libs) // Or the latest version
}

// Maven publishing configuration
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.primetechsoltutions"
                artifactId = "network-measurement-unified-sdk"
                version = project.version.toString()
            }

            create<MavenPublication>("debug") {
                from(components["debug"])
                groupId = "com.github.primetechsoltutions"
                artifactId = "network-measurement-unified-sdk-debug"
                version = project.version.toString()
            }
        }
    }
}
