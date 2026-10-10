plugins {
    jacoco
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val internalVersionCode =
    providers.environmentVariable("ANAALARM_INTERNAL_VERSION_CODE")
        .orElse("1")
        .map { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
                ?: throw GradleException(
                    "ANAALARM_INTERNAL_VERSION_CODE must be an integer from 1 to 2100000000."
                )
        }

val internalVersionSuffix =
    providers.environmentVariable("ANAALARM_INTERNAL_VERSION_SUFFIX")
        .orElse("local")
        .map { raw ->
            raw.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) }
                ?: throw GradleException(
                    "ANAALARM_INTERNAL_VERSION_SUFFIX must contain 1-64 safe filename characters."
                )
        }

android {
    namespace = "com.anaalarm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anaalarm"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("internal") {
            initWith(getByName("release"))
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal.${internalVersionSuffix.get()}"
            resValue("string", "internal_app_name", "AnaAlarm Internal")
            manifestPlaceholders["appLabel"] = "@string/internal_app_name"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testCoverage { jacocoVersion = "0.8.15" }

    testOptions {
        animationsDisabled = true
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        // MockK inline mocking requires byte-buddy-agent self-attach, which JDK 21+ blocks by
        // default. Harmless on JDK 17 where CI runs.
        unitTests.all { test ->
            test.extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
            test.jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

androidComponents {
    onVariants(selector().withBuildType("internal")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(internalVersionCode)
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")

    androidTestImplementation(composeBom)
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // ui-test-manifest omitted: tests launch real Activities (MainActivity / WakeUpActivity).
}

// Merge unit and adb-driven device coverage against the original (uninstrumented) classes.
jacoco { toolVersion = "0.8.15" }
val coverageExclusions = listOf("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/*_Impl*", "com/anaalarm/debug/**")
val originalClasses = files(
    fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) { exclude(coverageExclusions) },
    fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) { exclude(coverageExclusions) }
)
val coverageFiles = files(
    fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage")) { include("**/*.exec", "**/*.ec") },
    fileTree(rootProject.layout.projectDirectory.dir("artifacts")) { include("**/*.ec") }
)
val voiceCoveragePatterns = listOf(
    "com/anaalarm/voice/**",
    "com/anaalarm/ui/avatar/**",
    "com/anaalarm/ui/wakeup/SessionController*", "com/anaalarm/ui/wakeup/SessionListenCycle*",
    "com/anaalarm/ui/wakeup/SessionTurnPipeline*", "com/anaalarm/ui/wakeup/SessionDismissal*",
    "com/anaalarm/ui/wakeup/SessionPhrases*", "com/anaalarm/ui/wakeup/SessionTiming*",
    "com/anaalarm/ui/wakeup/MorningRoutine*"
)
tasks.register<JacocoReport>("voiceCoverageReport") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(originalClasses.asFileTree.matching { include(voiceCoveragePatterns) })
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(coverageFiles)
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JacocoReport>("appCoverageReport") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(originalClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(coverageFiles)
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JacocoCoverageVerification>("verifyVoiceCoverage") {
    dependsOn("voiceCoverageReport")
    classDirectories.setFrom(originalClasses.asFileTree.matching { include(voiceCoveragePatterns) })
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(coverageFiles)
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.90".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.85".toBigDecimal() }
        }
    }
    doFirst {
        check(classDirectories.files.any { it.exists() }) { "No voice classes found for coverage verification" }
        check(executionData.files.any { it.exists() }) { "No coverage measurements found" }
    }
}
