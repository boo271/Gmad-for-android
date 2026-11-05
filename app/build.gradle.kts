import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun buildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val workshopApiBase = providers.gradleProperty("gmad.workshopApi").orElse("").get()
val updateApiUrl = providers.gradleProperty("gmad.updateApi")
    .orElse("https://api.github.com/repos/boo271/Gmad-for-android/releases/latest")
    .get()

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.gmodkit.tools"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gmodkit.tools"
        minSdk = 24
        targetSdk = 35
        versionCode = 1024201
        versionName = "2.1"

        buildConfigField("String", "WORKSHOP_API_BASE", buildConfigString(workshopApiBase))
        buildConfigField("String", "UPDATE_API_URL", buildConfigString(updateApiUrl))
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // The debug build installs next to an existing release so testing
            // never overwrites the user's installed app or its data.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The recorded fixtures cover the protocol offline; -Pgmad.live=1 additionally
// runs one real download end to end.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("gmad.live").orNull?.let {
        systemProperty("gmad.live", it)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
    // Unit tests read the recorded Steam fixtures with a real org.json; the
    // mockable android.jar stubs these classes out.
    testImplementation("org.json:json:20240303")
}
