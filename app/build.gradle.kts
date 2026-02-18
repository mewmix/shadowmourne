import java.util.Properties
import java.io.FileInputStream
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gitCommitHash = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
    process.inputStream.reader().use { it.readText() }.trim()
} catch (e: Exception) {
    "unknown"
}

android {
    namespace = "com.mewmix.glaive"
    compileSdk = 34

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.mewmix.glaive"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.1.3"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        multiDexEnabled = true
        buildConfigField("String", "GIT_COMMIT_HASH", "\"$gitCommitHash\"")
        // Build a GitHub commit URL based on the repository remote
        val remoteUrl = try {
            val process = ProcessBuilder("git", "config", "--get", "remote.origin.url").start()
            process.inputStream.reader().use { it.readText() }.trim()
        } catch (e: Exception) { "" }

        fun toHttpsBase(url: String): String {
            if (url.isBlank()) return ""
            return when {
                url.startsWith("git@") -> {
                    val cleaned = url.removePrefix("git@")
                    val parts = cleaned.split(":", limit = 2)
                    if (parts.size == 2) {
                        val host = parts[0]
                        val path = parts[1].removeSuffix(".git").removeSuffix("/")
                        "https://$host/$path"
                    } else ""
                }
                url.startsWith("ssh://git@") -> {
                    val cleaned = url.removePrefix("ssh://git@")
                    val parts = cleaned.split("/", limit = 2)
                    if (parts.size == 2) {
                        val host = parts[0]
                        val path = parts[1].removeSuffix(".git").removeSuffix("/")
                        "https://$host/$path"
                    } else ""
                }
                url.startsWith("https://") || url.startsWith("http://") -> {
                    url.removeSuffix(".git").removeSuffix("/")
                }
                else -> url.removeSuffix(".git").removeSuffix("/")
            }
        }

        val httpsBase = toHttpsBase(remoteUrl)
        val gitCommitUrl = if (gitCommitHash.isNotBlank() && gitCommitHash != "unknown" && httpsBase.isNotBlank())
            "$httpsBase/commit/$gitCommitHash" else ""
        buildConfigField("String", "GIT_COMMIT_URL", "\"$gitCommitUrl\"")
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = Properties()
            val isReleaseBuild = gradle.startParameter.taskNames.any {
                it.contains("Release", ignoreCase = true)
            }
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            } else if (isReleaseBuild) {
                throw GradleException("Missing keystore.properties for release signing.")
            }
            val storeFilePath = keystoreProperties.getProperty("storeFile")
            val storePasswordValue = keystoreProperties.getProperty("storePassword")
            val keyAliasValue = keystoreProperties.getProperty("keyAlias")
            val keyPasswordValue = keystoreProperties.getProperty("keyPassword")
            if (isReleaseBuild) {
                if (storeFilePath.isNullOrBlank()) {
                    throw GradleException("Missing storeFile in keystore.properties for release signing.")
                }
                if (storePasswordValue.isNullOrBlank()) {
                    throw GradleException("Missing storePassword in keystore.properties for release signing.")
                }
                if (keyAliasValue.isNullOrBlank()) {
                    throw GradleException("Missing keyAlias in keystore.properties for release signing.")
                }
                if (keyPasswordValue.isNullOrBlank()) {
                    throw GradleException("Missing keyPassword in keystore.properties for release signing.")
                }
            }
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
            }
            if (!storePasswordValue.isNullOrBlank()) {
                storePassword = storePasswordValue
            }
            if (!keyAliasValue.isNullOrBlank()) {
                keyAlias = keyAliasValue
            }
            if (!keyPasswordValue.isNullOrBlank()) {
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "Shadowmourne-${versionName}.apk"
            }
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("com.github.luben:zstd-jni:1.5.5-11@aar")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Icons used: AspectRatio, CallSplit, etc.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.github.luben:zstd-jni:1.5.5-11")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
