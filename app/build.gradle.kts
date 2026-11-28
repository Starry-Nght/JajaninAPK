plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

// google-services.json hanya datang dari Firebase project pengguna; tanpa file itu
// plugin akan gagal build, jadi plugin hanya dipasang ketika file sudah tersedia.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val supabaseUrl = providers.gradleProperty("SUPABASE_URL")
    .orElse("https://yezxyhpczfnkcndfpuht.supabase.co").get()
val supabasePublishableKey = providers.gradleProperty("SUPABASE_PUBLISHABLE_KEY")
    .orElse("sb_publishable_vj7akRru3TvuUmkugtyRBg_0oRAQck6").get()
val otaVersionCode = providers.gradleProperty("VERSION_CODE").map(String::toInt).orElse(48).get()
val otaVersionName = providers.gradleProperty("VERSION_NAME").orElse("2.27").get()

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android { namespace = "com.jajanin.app"; compileSdk = 35
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
        }
    }
    defaultConfig {
        applicationId = "com.jajanin.app"; minSdk = 24; targetSdk = 35
        versionCode = otaVersionCode; versionName = otaVersionName
        buildConfigField("String", "SUPABASE_URL", supabaseUrl.asBuildConfigString())
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", supabasePublishableKey.asBuildConfigString())
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(platform("io.github.jan-tennert.supabase:bom:2.6.0"))
    implementation("io.github.jan-tennert.supabase:gotrue-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.github.QWEA0:liquidglass:v2.0.11")
}

configurations.configureEach {
    resolutionStrategy.force("androidx.browser:browser:1.8.0")
}
