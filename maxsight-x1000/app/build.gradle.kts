plugins { id("com.android.application") }

android {
    namespace = "com.vhanma.maxsightx1000"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vhanma.maxsightx1000"
        minSdk = 26
        targetSdk = 36
        versionCode = 1000
        versionName = "1.0.0-x1000"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { applicationIdSuffix = ".debug" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val cameraX = "1.6.2"
    val media3 = "1.11.1"
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-video:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
}
