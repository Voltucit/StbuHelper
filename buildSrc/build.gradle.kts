import org.gradle.api.tasks.compile.JavaCompile

// Only the built-in Java plugin is needed for this build-time configuration class.
plugins { java }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
