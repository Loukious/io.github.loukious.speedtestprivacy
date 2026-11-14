plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "io.github.loukious.speedtestprivacy"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.loukious.speedtestprivacy"
        minSdk = 27
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
        testInstrumentationRunner = "io.github.loukious.speedtestprivacy.tests.DiscoveryInstrumentation"
    }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    packaging { resources { merges += "META-INF/xposed/*" } }
}
base.archivesName.set("SpeedtestPrivacy-v${android.defaultConfig.versionName}")
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:101.0.0")
    implementation("org.luckypray:dexkit:2.2.0")
    testImplementation("junit:junit:4.13.2")
}

/** Generated assets stay in the instrumentation APK; fixture classes never enter production DEX. */
abstract class DiscoveryFixtureAssets : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFiles
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NAME_ONLY)
    abstract val fixtureApks: org.gradle.api.file.ConfigurableFileCollection

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun assembleAssets() {
        val root = outputDirectory.get().asFile
        if (root.exists() && !root.deleteRecursively()) error("Cannot replace generated discovery assets")
        val assets = root.resolve("discovery-fixtures")
        check(assets.mkdirs()) { "Cannot create generated discovery assets" }
        fixtureApks.files.sortedBy { it.name }.forEach { fixture ->
            check(fixture.isFile) { "Missing generated discovery fixture: $fixture" }
            fixture.copyTo(assets.resolve(fixture.name), overwrite = true)
        }
    }
}

val discoverySdk = androidComponents.sdkComponents.sdkDirectory
val discoveryD8 = discoverySdk.map { it.file("build-tools/36.0.0/lib/d8.jar") }
val discoveryAndroidJar = discoverySdk.map { it.file("platforms/android-36/android.jar") }
val discoveryArchives = mutableListOf<org.gradle.api.tasks.TaskProvider<org.gradle.api.tasks.bundling.Zip>>()
var discoveryDexA: org.gradle.api.tasks.TaskProvider<org.gradle.api.tasks.JavaExec>? = null
val discoveryDexDirectoryA = layout.buildDirectory.dir("discoveryFixtures/a/dex")

for (fixture in listOf("a", "b", "c", "d", "e")) {
    val suffix = fixture.uppercase()
    val compiled = tasks.register<org.gradle.api.tasks.compile.JavaCompile>("compileDiscoveryFixture$suffix") {
        source(fileTree("src/discoveryFixtures/$fixture") { include("**/*.java") })
        classpath = files()
        options.release.set(8)
        options.encoding = "UTF-8"
        destinationDirectory.set(layout.buildDirectory.dir("discoveryFixtures/$fixture/classes"))
    }
    val jar = tasks.register<org.gradle.api.tasks.bundling.Jar>("jarDiscoveryFixture$suffix") {
        dependsOn(compiled)
        from(compiled.flatMap { it.destinationDirectory })
        archiveFileName.set("fixture-$fixture.jar")
        destinationDirectory.set(layout.buildDirectory.dir("discoveryFixtures/$fixture"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    val dexDirectory = layout.buildDirectory.dir("discoveryFixtures/$fixture/dex")
    val dex = tasks.register<org.gradle.api.tasks.JavaExec>("dexDiscoveryFixture$suffix") {
        dependsOn(jar)
        inputs.file(jar.flatMap { it.archiveFile })
        inputs.file(discoveryD8)
        inputs.file(discoveryAndroidJar)
        outputs.dir(dexDirectory)
        classpath = files(discoveryD8)
        mainClass.set("com.android.tools.r8.D8")
        doFirst {
            val output = dexDirectory.get().asFile
            if (output.exists() && !output.deleteRecursively()) error("Cannot replace fixture DEX output")
            check(output.mkdirs()) { "Cannot create fixture DEX output" }
            setArgs(listOf("--min-api", "27", "--lib", discoveryAndroidJar.get().asFile.absolutePath,
                "--output", output.absolutePath, jar.get().archiveFile.get().asFile.absolutePath))
        }
    }
    if (fixture == "a") discoveryDexA = dex
    discoveryArchives += tasks.register<org.gradle.api.tasks.bundling.Zip>("apkDiscoveryFixture$suffix") {
        dependsOn(dex)
        from(dexDirectory) { include("classes*.dex") }
        archiveFileName.set("fixture-$fixture.apk")
        destinationDirectory.set(layout.buildDirectory.dir("discoveryFixtureApks"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

discoveryArchives += tasks.register<org.gradle.api.tasks.bundling.Zip>("apkDiscoveryFixtureDuplicate") {
    dependsOn(checkNotNull(discoveryDexA))
    from(discoveryDexDirectoryA) { include("classes*.dex") }
    archiveFileName.set("fixture-duplicate.apk")
    destinationDirectory.set(layout.buildDirectory.dir("discoveryFixtureApks"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val generatedDiscoveryAssets = tasks.register<DiscoveryFixtureAssets>("generateDiscoveryFixtureAssets") {
    dependsOn(discoveryArchives)
    fixtureApks.from(discoveryArchives.map { it.flatMap { archive -> archive.archiveFile } })
    outputDirectory.set(layout.buildDirectory.dir("generated/discoveryFixtureAssets"))
}

androidComponents.onVariants { variant ->
    variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(
        generatedDiscoveryAssets, DiscoveryFixtureAssets::outputDirectory
    )
}
