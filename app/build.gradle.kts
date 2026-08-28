plugins {
    id("com.android.application")
}

android { namespace = "io.github.giuploader"; compileSdk = 37
    defaultConfig { applicationId = "io.github.giuploader"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0" }
}

dependencies { implementation("androidx.core:core-ktx:1.17.0") }
