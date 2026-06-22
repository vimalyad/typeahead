plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":shared"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // We own cache routing (consistent-hash ring over standalone nodes), so use the Lettuce
    // client directly rather than spring-data-redis's single-Redis RedisTemplate.
    implementation("io.lettuce:lettuce-core")
    implementation("com.google.guava:guava:33.3.1-jre")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
