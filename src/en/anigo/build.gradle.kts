apply(plugin = "com.android.application")
apply(plugin = "kotlin-android")

ext {
    extName = "Anigo"
    pkgNameSuffix = "en.anigo"
    extClass = ".Anigo"
    extVersionCode = 1
    libVersion = "14"
}

apply(from = "$rootDir/common.gradle")