package ru.akvine.zond.loaders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Библиотеки проекта находятся по его pom.xml в локальном репозитории Maven, без запуска самого Maven
 */
class MavenClasspathResolverTest {
    private static final String GROUP = "org.demo";

    @TempDir
    Path dir;

    private Path repository;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        repository = dir.resolve("repository");
        project = Files.createDirectories(dir.resolve("project"));

        // Родитель задает версии, как spring-boot-starter-parent: напрямую и через свойство
        install("platform", "1.0", false, """
                <project>
                  <groupId>org.demo</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0</version>
                  <properties>
                    <lib-a.version>2.0</lib-a.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>org.demo</groupId><artifactId>lib-a</artifactId><version>${lib-a.version}</version></dependency>
                      <dependency><groupId>org.demo</groupId><artifactId>lib-b</artifactId><version>3.0</version></dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        // Зависимости зависимости: обычная, необязательная и тестовая
        install("lib-a", "2.0", true, """
                <project>
                  <groupId>org.demo</groupId>
                  <artifactId>lib-a</artifactId>
                  <version>2.0</version>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>some-parent</artifactId>
                    <version>7</version>
                    <relativePath>org.demo:some-parent</relativePath>
                  </parent>
                  <dependencies>
                    <dependency><groupId>org.demo</groupId><artifactId>lib-c</artifactId><version>1.5</version></dependency>
                    <dependency><groupId>org.demo</groupId><artifactId>lib-optional</artifactId><version>1.0</version><optional>true</optional></dependency>
                    <dependency><groupId>org.demo</groupId><artifactId>lib-test</artifactId><version>1.0</version><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """);
        install("lib-b", "3.0", true, pom("lib-b", "3.0"));
        install("lib-b", "9.0", true, pom("lib-b", "9.0"));
        install("lib-c", "1.5", true, pom("lib-c", "1.5"));
        install("lib-optional", "1.0", true, pom("lib-optional", "1.0"));
        install("lib-test", "1.0", true, pom("lib-test", "1.0"));
        install("lib-d", "1.9", true, pom("lib-d", "1.9"));
        install("lib-d", "1.10", true, pom("lib-d", "1.10"));
    }

    @Test
    void resolvesVersionsFromParentAndFollowsTransitiveDependencies() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>platform</artifactId>
                    <version>1.0</version>
                    <relativePath/>
                  </parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency><groupId>org.demo</groupId><artifactId>lib-a</artifactId></dependency>
                    <dependency><groupId>org.demo</groupId><artifactId>lib-b</artifactId></dependency>
                    <dependency><groupId>org.demo</groupId><artifactId>lib-d</artifactId></dependency>
                    <dependency><groupId>org.demo</groupId><artifactId>lib-missing</artifactId><version>9.9</version></dependency>
                  </dependencies>
                </project>
                """);

        MavenClasspathResolver.Resolved resolved = new MavenClasspathResolver(repository).resolve(project);

        // lib-a и lib-b - версии от родителя, а не самые свежие; lib-c пришла следом за lib-a;
        // у lib-d версия нигде не задана - берется самая свежая из скачанных (1.10 новее 1.9);
        // необязательная и тестовая зависимости библиотеки в проект не попадают
        assertThat(names(resolved.jars()))
                .containsExactlyInAnyOrder("lib-a-2.0.jar", "lib-b-3.0.jar", "lib-c-1.5.jar", "lib-d-1.10.jar");
        assertThat(resolved.missing()).containsExactly("org.demo:lib-missing:9.9");
        assertThat(resolved.repository()).isEqualTo(repository);
    }

    @Test
    void resolvesVersionsFromImportedBomAndModules() throws IOException {
        // Версии приходят из BOM, подключенного через scope import; модуль наследует их от корневого pom.xml
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <groupId>org.demo</groupId>
                  <artifactId>root</artifactId>
                  <version>0.1</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.demo</groupId>
                        <artifactId>platform</artifactId>
                        <version>1.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Path module = Files.createDirectories(project.resolve("service"));
        Files.writeString(module.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>root</artifactId>
                    <version>0.1</version>
                  </parent>
                  <artifactId>service</artifactId>
                  <dependencies>
                    <dependency><groupId>org.demo</groupId><artifactId>lib-b</artifactId></dependency>
                    <dependency><groupId>org.demo</groupId><artifactId>root</artifactId><version>${project.version}</version></dependency>
                  </dependencies>
                </project>
                """);
        // Копия pom.xml в каталоге сборки проектом не считается
        Path target = Files.createDirectories(project.resolve("target/classes"));
        Files.writeString(target.resolve("pom.xml"), "<project><dependencies><dependency>"
                + "<groupId>org.demo</groupId><artifactId>lib-c</artifactId><version>1.5</version>"
                + "</dependency></dependencies></project>");

        MavenClasspathResolver.Resolved resolved = new MavenClasspathResolver(repository).resolve(project);

        // Собственный модуль проекта библиотекой не является и в "не найдено" не попадает
        assertThat(names(resolved.jars())).containsExactly("lib-b-3.0.jar");
        assertThat(resolved.missing()).isEmpty();
    }

    @Test
    void notMavenProjectHasNoLibraries() throws IOException {
        Files.writeString(project.resolve("build.gradle"), "dependencies { implementation 'org.demo:lib-b:3.0' }");

        MavenClasspathResolver.Resolved resolved = new MavenClasspathResolver(repository).resolve(project);

        assertThat(resolved.jars()).isEmpty();
        assertThat(resolved.missing()).isEmpty();
    }

    private void install(String artifact, String version, boolean withJar, String pom) throws IOException {
        Path directory = Files.createDirectories(repository.resolve("org/demo").resolve(artifact).resolve(version));
        Files.writeString(directory.resolve(artifact + "-" + version + ".pom"), pom);
        if (withJar) {
            Files.write(directory.resolve(artifact + "-" + version + ".jar"), new byte[0]);
        }
    }

    private String pom(String artifact, String version) {
        return "<project><groupId>" + GROUP + "</groupId><artifactId>" + artifact + "</artifactId><version>"
                + version + "</version></project>";
    }

    private List<String> names(List<Path> jars) {
        return jars.stream().map(jar -> jar.getFileName().toString()).toList();
    }
}
