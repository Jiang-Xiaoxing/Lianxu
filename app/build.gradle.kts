plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "app.lianxu.independent"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.lianxu.independent"
        minSdk = 26
        targetSdk = 37
        versionCode = 59
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes { getByName("debug") { applicationIdSuffix = ".companionstage1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}
tasks.withType<Test>().configureEach { maxHeapSize = "160m"; maxParallelForks = 1 }
dependencies {
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
