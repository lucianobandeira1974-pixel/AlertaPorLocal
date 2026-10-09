plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "br.com.alertaporlocal"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.com.alertaporlocal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
