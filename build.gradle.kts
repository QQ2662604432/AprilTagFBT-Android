// 根级 build.gradle.kts
plugins {
    id("com.android.application") version "8.2.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
