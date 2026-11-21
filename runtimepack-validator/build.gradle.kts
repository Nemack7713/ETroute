plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val candidate = providers.gradleProperty("runtimepackBuildDir").orNull
    ?: error("runtimepackBuildDir must point to a verified generated candidate")
android {
    namespace = "org.nemack.universalfilelab.etroute.runtimepack"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "org.nemack.universalfilelab.etroute.runtimepack.manualprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "3.14.4-manual-probe-v2"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main") {
        jniLibs.srcDir("$candidate/jniLibs")
        assets.srcDir("$candidate/assets")
    }
    androidResources { ignoreAssetsPattern = "!.git:!.svn" }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/*.so"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":android"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}
