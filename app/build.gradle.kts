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
// A chave e a senha ficam nos segredos do GitHub (CHAVE_APK e SENHA_APK), fora do código
val chave = System.getenv("CHAVE_APK")
val senha = System.getenv("SENHA_APK")
if (!chave.isNullOrBlank() && !senha.isNullOrBlank()) {
val jks = rootProject.file("chave-gerada.jks")
jks.writeBytes(Base64.getMimeDecoder().decode(chave.trim()))
storeFile = jks
storePassword = senha
keyAlias = "organizar"
keyPassword = senha
} else if (System.getenv("CI") != null) {
// Sem a chave certa o APK não instala por cima do app atual: compila (para achar erros), mas não monta o APK
tasks.configureEach {
if (name == "packageDebug") doFirst { throw GradleException("Faltam os segredos CHAVE_APK e SENHA_APK no GitHub (Settings > Secrets and variables > Actions).") }
}
}
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
implementation("androidx.core:core:1.12.0")
}
