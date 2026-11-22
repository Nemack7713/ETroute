import java.security.MessageDigest
import groovy.json.JsonOutput

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
        applicationId = "org.nemack.universalfilelab.etroute.runtimepack.sourcecontext"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "3.14.4-source-context-v9"
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

// Snapshot only project-owned Kotlin sources; never include Gradle properties, keys, credentials or external dependencies.
val sourceContextAssets = layout.buildDirectory.dir("generated/sourceContextAssets")
val sourceContextRoots = listOf(rootProject.file("android/src/main/java"), file("src/main/java"))
val generateSourceContext by tasks.registering {
    inputs.files(sourceContextRoots.map { root -> fileTree(root) { include("**/*.kt") } })
    outputs.dir(sourceContextAssets)
    doLast {
        val files = sourceContextRoots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
        check(files.size <= 128)
        check(files.sumOf { it.length() } <= 1024 * 1024)
        val entries = files.map { source ->
            val text = source.readText(Charsets.UTF_8)
            val packageName = Regex("(?m)^package\\s+([A-Za-z0-9_.]+)").find(text)?.groupValues?.get(1)
                ?: error("Source package missing: ${source.name}")
            mapOf("path" to source.relativeTo(rootProject.projectDir).invariantSeparatorsPath,
                "package" to packageName, "file" to source.name,
                "sha256" to MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) },
                "lines" to text.lineSequence().toList())
        }
        val json = JsonOutput.toJson(mapOf("schemaVersion" to 1, "files" to entries))
        check(json.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
        val target = sourceContextAssets.get().file("diagnostics/source-context-v1.json").asFile
        target.parentFile.mkdirs(); target.writeText(json, Charsets.UTF_8)
    }
}
android.sourceSets.getByName("debug").assets.srcDir(sourceContextAssets)
tasks.named("preBuild") { dependsOn(generateSourceContext) }
