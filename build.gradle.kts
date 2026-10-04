plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}
android {
    namespace = "top.sagrus.cam"
    compileSdk = 34
    defaultConfig { applicationId = "top.sagrus.cam"; minSdk = 29; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("kt"))
            res.setSrcDirs(listOf("res"))
        }
    }
}
