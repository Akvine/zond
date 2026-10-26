package ru.akvine.zond.loaders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Библиотеки проекта Gradle находятся по build.gradle, каталогу версий и gradle.properties в кэше Gradle,
 * без запуска самого Gradle
 */
class GradleClasspathTest {
    @TempDir
    Path dir;

    private Path cache;
    private Path project;
    private MavenClasspathResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        cache = dir.resolve("files-2.1");
        project = Files.createDirectories(dir.resolve("project"));
        resolver = new MavenClasspathResolver(dir.resolve("m2"), cache);

        install("org.demo", "lib-a", "1.0", """
                <project>
                  <groupId>org.demo</groupId><artifactId>lib-a</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>org.demo</groupId><artifactId>transitive</artifactId><version>4.0</version></dependency>
                  </dependencies>
                </project>
                """);
        install("org.demo", "transitive", "4.0", "<project><artifactId>transitive</artifactId></project>");
        install("org.demo", "lib-b", "2.0", "<project><artifactId>lib-b</artifactId></project>");
        install("org.demo", "lib-c", "3.1", "<project><artifactId>lib-c</artifactId></project>");
        install("org.demo", "managed", "5.5", "<project><artifactId>managed</artifactId></project>");
        install("org.demo", "test-only", "1.0", "<project><artifactId>test-only</artifactId></project>");
        installPom("org.demo", "demo-bom", "9.0", """
                <project>
                  <groupId>org.demo</groupId><artifactId>demo-bom</artifactId><version>9.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>org.demo</groupId><artifactId>managed</artifactId><version>5.5</version></dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
    }

    @Test
    void readsBuildGradleCatalogAndProperties() throws IOException {
        Files.writeString(project.resolve("gradle.properties"), "libCVersion=3.1\n");
        Files.createDirectories(project.resolve("gradle"));
        Files.writeString(project.resolve("gradle/libs.versions.toml"), """
                [versions]
                libb = "2.0"

                [libraries]
                demo-lib-b = { module = "org.demo:lib-b", version.ref = "libb" }
                """);
        Files.writeString(project.resolve("build.gradle"), """
                dependencies {
                    implementation platform('org.demo:demo-bom:9.0')
                    implementation 'org.demo:lib-a:1.0'
                    implementation libs.demo.lib.b
                    compileOnly group: 'org.demo', name: 'lib-c', version: "$libCVersion"
                    implementation 'org.demo:managed'
                    testImplementation 'org.demo:test-only:1.0'
                }
                """);

        MavenClasspathResolver.Resolved resolved = resolver.resolve(project);

        // Зависимость зависимости - из ее pom в кэше; тестовая библиотека в код приложения не попадает
        assertThat(resolved.jars()).extracting(jar -> jar.getFileName().toString())
                .containsExactlyInAnyOrder("lib-a-1.0.jar", "transitive-4.0.jar", "lib-b-2.0.jar", "lib-c-3.1.jar",
                        "managed-5.5.jar");
        assertThat(resolved.missing()).isEmpty();
    }

    @Test
    void kotlinScriptAndMissingLibrary() throws IOException {
        Files.writeString(project.resolve("build.gradle.kts"), """
                dependencies {
                    implementation("org.demo:lib-b:2.0")
                    api("org.demo:absent:1.0")
                }
                """);

        MavenClasspathResolver.Resolved resolved = resolver.resolve(project);

        assertThat(resolved.jars()).extracting(jar -> jar.getFileName().toString()).containsExactly("lib-b-2.0.jar");
        assertThat(resolved.missing()).containsExactly("org.demo:absent:1.0");
    }

    // Кэш Gradle: jar и pom в разных подкаталогах по хешу
    private void install(String group, String artifact, String version, String pom) throws IOException {
        Path jarDirectory = Files.createDirectories(cache.resolve(group).resolve(artifact).resolve(version).resolve("a1b2"));
        Files.writeString(jarDirectory.resolve(artifact + "-" + version + ".jar"), "jar");
        installPom(group, artifact, version, pom);
    }

    private void installPom(String group, String artifact, String version, String pom) throws IOException {
        Path pomDirectory = Files.createDirectories(cache.resolve(group).resolve(artifact).resolve(version).resolve("c3d4"));
        Files.writeString(pomDirectory.resolve(artifact + "-" + version + ".pom"), pom);
    }
}
