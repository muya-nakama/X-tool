plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val currentInfo = groovy.json.JsonSlurper().parse(rootProject.file("new/version.json")) as Map<*, *>
val previousInfo = groovy.json.JsonSlurper().parse(rootProject.file("old/version.json")) as Map<*, *>
val previousSources = layout.buildDirectory.dir("generated/previous/java")
val previousManifest = layout.buildDirectory.file("generated/previous/AndroidManifest.xml")
val generatePreviousVersion by tasks.registering {
    inputs.dir(rootProject.file("old"))
    inputs.file("src/main/AndroidManifest.xml")
    outputs.dir(previousSources)
    outputs.file(previousManifest)
    doLast {
        val target = previousSources.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        rootProject.file("old").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { source ->
            var text = source.readText().replace("package jp.muya.xsaver", "package jp.muya.xsaver.legacy")
            if (source.name == "MainActivity.kt") {
                text = text.replace("package jp.muya.xsaver.legacy", "package jp.muya.xsaver.legacy\n\nimport jp.muya.xsaver.VersionSwitcher", true)
                    .replace("VersionSwitcher.CURRENT", "VersionSwitcher.PREVIOUS")
                if (!text.contains("VersionSwitcher.addTo")) {
                    text = text.replace("        root.addView(text(\"X保存\", 30f)",
                        "        VersionSwitcher.addTo(root, this, VersionSwitcher.PREVIOUS)\n        root.addView(text(\"X保存\", 30f)")
                }
            }
            target.resolve(source.name).writeText(text)
        }
        var manifest = file("src/main/AndroidManifest.xml").readText()
        if (rootProject.file("old/LoginActivity.kt").exists()) {
            manifest = manifest.replace("        <service android:name=\".DownloadService\"",
                "        <activity android:name=\".legacy.LoginActivity\" android:exported=\"false\" android:process=\":login\" android:windowSoftInputMode=\"adjustResize\" />\n        <service android:name=\".DownloadService\"")
        }
        previousManifest.get().asFile.apply { parentFile.mkdirs(); writeText(manifest) }
    }
}
tasks.named("preBuild").configure { dependsOn(generatePreviousVersion) }
android {
    namespace = "jp.muya.xsaver"
    compileSdk = 35
    defaultConfig {
        applicationId = "jp.muya.xsaver"
        minSdk = 29
        targetSdk = 35
        versionCode = (currentInfo["versionCode"] as Number).toInt()
        versionName = currentInfo["version"].toString()
        buildConfigField("String", "PREVIOUS_VERSION", "\"${previousInfo["version"]}\"")
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    sourceSets["main"].java.setSrcDirs(listOf("../shell", "../new", previousSources))
    sourceSets["main"].manifest.srcFile(previousManifest)
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
}
