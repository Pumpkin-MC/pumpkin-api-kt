pluginManagement {
    repositories {
        val ciRepository = providers.environmentVariable("PUMPKIN_CI_REPOSITORY").orNull
        if (ciRepository != null) {
            maven { url = uri(ciRepository) }
        } else {
            mavenLocal()
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "pumpkin-example"
