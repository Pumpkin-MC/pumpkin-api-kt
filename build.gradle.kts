plugins {
    base
    alias(libs.plugins.kotlinMultiplatform) apply false
}

tasks.named("build") {
    dependsOn(":api:build", ":gradle-plugin:build")
}

tasks.named("clean") {
    dependsOn(":api:clean", ":gradle-plugin:clean")
}
