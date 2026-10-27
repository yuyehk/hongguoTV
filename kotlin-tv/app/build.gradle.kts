import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hongguotv.nativeapp"
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 15
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.11.1-android14-accounts"
    }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.systemProperty("maven.repo.local", System.getenv("HONGGUOTV_TEST_MAVEN_CACHE") ?: rootProject.layout.buildDirectory.dir("robolectric-maven").get().asFile.absolutePath)
            it.maxHeapSize = "2g"
        }
    }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        getByName("debug") {
            if (System.getenv("HONGGUOTV_SIGN_DEBUG") == "1") signingConfig = signingConfigs.getByName("delivery")
        }
        getByName("release") { signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1") {
        exclude(group = "org.robolectric", module = "nativeruntime-dist-compat")
    }
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.core:core:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
}
