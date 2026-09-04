plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.cokkles.gpos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cokkles.gpos"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "0.7.0-dev"

        buildConfigField(
            "String",
            "GPOS_BACKEND_URL",
            "\"https://script.google.com/macros/s/AKfycbw4Rj-zD7L9TCi3ldYobavsKDiyUJ3hLJWhOUuu5PVc83NnzKc7xTdVzNykSgt3h5zSfA/exec\"",
        )
        buildConfigField(
            "String",
            "GPOS_GOOGLE_SERVER_CLIENT_ID",
            "\"441009275873-qnf9c9n1o3l9tl9c76t2821hm8tectfl.apps.googleusercontent.com\"",
        )
        buildConfigField(
            "String",
            "GPOS_CHECKPOINT_CERT_SHA1",
            "\"D2:A0:80:66:49:D0:00:B1:B8:4F:FF:45:A9:C6:DF:76:8D:FC:31:EA\"",
        )
        buildConfigField(
            "String",
            "GPOS_ANDROID_PACKAGE",
            "\"com.cokkles.gpos\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("checkpoint") {
            storeFile = rootProject.file("keystore/gpos-checkpoint-debug.keystore")
            storePassword = "gpos-checkpoint"
            keyAlias = "gpos-checkpoint"
            keyPassword = "gpos-checkpoint"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("checkpoint")
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.work:work-runtime:2.11.2")

    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
