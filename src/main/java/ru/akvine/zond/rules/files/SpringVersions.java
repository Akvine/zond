package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.TextFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Версии Spring Boot, названные в файлах сборки проекта
 */
@UtilityClass
public class SpringVersions {
    private static final String NUMBER = "(\\d+)\\.(\\d+)(?:\\.(\\d+))?";

    // id 'org.springframework.boot' version '3.2.1', spring-boot-gradle-plugin:3.2.1, springBootVersion = '3.2.1',
    // <spring-boot.version>3.2.1</spring-boot.version>
    private static final List<Pattern> INLINE = List.of(
            Pattern.compile("org\\.springframework\\.boot['\")]*\\s+version\\s*\\(?\\s*['\"]" + NUMBER),
            Pattern.compile("spring-boot-(?:gradle-plugin|dependencies|starter-parent):" + NUMBER),
            Pattern.compile("(?i)spring[-_.]?boot[-_.]?version['\"]?\\s*[=:>]\\s*['\"]?" + NUMBER));
    // <artifactId>spring-boot-starter-parent</artifactId> ... <version>3.2.1</version>
    private static final Pattern MAVEN_ARTIFACT =
            Pattern.compile("<artifactId>\\s*spring-boot-(starter-parent|dependencies)\\s*</artifactId>");
    private static final Pattern MAVEN_VERSION = Pattern.compile("<version>\\s*" + NUMBER);
    private static final int MAVEN_LOOKAHEAD = 3;

    // Jmix 1.x собран на Spring Boot 2.x и версию Boot в сборке не называет
    private static final Pattern JMIX_ONE = Pattern.compile(
            "io\\.jmix['\")]*\\s+version\\s*\\(?\\s*['\"]1\\.|jmix-gradle-plugin:1\\.|jmix-bom:1\\.");
    private static final Version JMIX_ONE_BOOT = new Version(2, 7, 0);

    public record Version(int major, int minor, int patch) {
        public boolean isAtLeast(int otherMajor, int otherMinor, int otherPatch) {
            if (major != otherMajor) {
                return major > otherMajor;
            }
            if (minor != otherMinor) {
                return minor > otherMinor;
            }
            return patch >= otherPatch;
        }
    }

    /**
     * @return все версии Spring Boot, найденные в pom.xml и build.gradle; у многомодульного проекта их может
     * быть несколько. Пусто, если версия задана не в файлах сборки
     */
    public List<Version> boot(List<TextFile> files) {
        List<Version> versions = new ArrayList<>();
        for (TextFile file : files) {
            if (!TextFiles.isPom(file) && !TextFiles.isGradle(file)) {
                continue;
            }
            List<String> lines = file.lines();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                for (Pattern pattern : INLINE) {
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        versions.add(version(matcher));
                    }
                }
                if (MAVEN_ARTIFACT.matcher(line).find()) {
                    for (int next = index; next < Math.min(lines.size(), index + MAVEN_LOOKAHEAD + 1); next++) {
                        Matcher matcher = MAVEN_VERSION.matcher(lines.get(next));
                        if (matcher.find()) {
                            versions.add(version(matcher));
                            break;
                        }
                    }
                }
                if (JMIX_ONE.matcher(line).find()) {
                    versions.add(JMIX_ONE_BOOT);
                }
            }
        }
        return versions;
    }

    private Version version(Matcher matcher) {
        return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)));
    }
}
