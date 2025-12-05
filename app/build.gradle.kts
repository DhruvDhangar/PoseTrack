plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.posetrack"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.posetrack"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // keep using version catalog for core libs
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Material Design (single entry - updated)
    implementation("com.google.android.material:material:1.13.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // CardView
    implementation("androidx.cardview:cardview:1.0.0")

    // ConstraintLayout (updated)
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // ViewPager2 for tabs (updated)
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // CameraX for SLAM (updated)
    val cameraxVersion = "1.5.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // OpenCV for feature detection (updated)
    implementation("org.opencv:opencv:4.12.0")
}
