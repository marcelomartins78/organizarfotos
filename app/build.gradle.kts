import java.util.Base64

plugins {
id("com.android.application")
id("org.jetbrains.kotlin.android")
}

android {
namespace = "com.marcelo.organizarfotos"
compileSdk = 34
defaultConfig {
applicationId = "com.marcelo.organizarfotos"
minSdk = 30
targetSdk = 34
versionCode = 1
versionName = "1.0"
}
signingConfigs {
getByName("debug") {
val jks = rootProject.file("chave-gerada.jks")
jks.writeBytes(Base64.getMimeDecoder().decode(rootProject.file("chave.txt").readText().trim()))
storeFile = jks
storePassword = "organizarfotos"
keyAlias = "organizar"
keyPassword = "organizarfotos"
}
}
compileOptions {
sourceCompatibility = JavaVersion.VERSION_17
targetCompatibility = JavaVersion.VERSION_17
}
kotlinOptions { jvmTarget = "17" }
}

dependencies {
implementation("androidx.exifinterface:exifinterface:1.3.7")
}
