package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.Dockerfiles;
import ru.akvine.zond.rules.files.JavaVersions;
import ru.akvine.zond.rules.files.TextFiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Образ для запуска несет Java старше той, под которую собирают проект: {@code FROM openjdk:11} при
 * {@code java.version} 21. JVM не загружает классы более новой версии - контейнер падает при запуске с
 * UnsupportedClassVersionError, и видно это только после сборки образа
 */
@Component
public class DockerJavaOlderThanBuildRule extends AbstractContextRule {
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final String FLAG_PREFIX = "--";
    private static final String AS = "as";
    private static final String VARIABLE = "$";
    // Образы с JVM, у которых версия Java стоит в начале тега: openjdk:11-jre, eclipse-temurin:17.0.9_9-jre-alpine
    private static final Pattern JAVA_IMAGE = Pattern.compile(
            "(?i)(^|/)(openjdk|eclipse-temurin|amazoncorretto|adoptopenjdk|ibm-semeru-runtimes|sapmachine"
                    + "|liberica-openjdk[\\w-]*|liberica-openjre[\\w-]*|zulu-openjdk[\\w-]*|jdk|jre|graalvm-community"
                    + "|graalvm-ce|jdk-community)$");
    // 11, 11-jre, 17.0.9_9-jre, 8u312-jdk, 1.8.0; тег вида jdk-21 или 21.0.2-jdk
    private static final Pattern TAG_VERSION =
            Pattern.compile("^(?:jdk-?|jre-?|java-?)?(1\\.)?(\\d{1,2})(?=$|[.\\-_u+])");
    // gcr.io/distroless/java17-debian12
    private static final Pattern NAME_VERSION = Pattern.compile("(?i)(^|/)java(\\d{1,2})(-|$)");

    @Override
    public String code() {
        return RuleCodes.DOCKER_JAVA_OLDER_THAN_BUILD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет Dockerfile с файлами сборки и ищет образ с Java старше той, под которую собирают проект";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isDockerfile(file)) {
                continue;
            }
            OptionalInt built = builtFor(file, context.textFiles());
            Optional<Dockerfiles.Instruction> from = runtimeImage(file);
            if (built.isEmpty() || from.isEmpty()) {
                continue;
            }
            String image = imageOf(from.get());
            OptionalInt provided = javaOf(image);
            if (provided.isPresent() && provided.getAsInt() < built.getAsInt()) {
                violations.add(violation(file.path(), from.get().line(),
                        "Образ '" + image + "' несет Java " + provided.getAsInt() + ", а проект собирают под Java "
                                + built.getAsInt() + ": JVM не загрузит классы более новой версии, и контейнер"
                                + " упадет при запуске с UnsupportedClassVersionError; возьмите образ с Java "
                                + built.getAsInt() + " или новее"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // FROM последнего этапа: в нем приложение и запускают. FROM builder и образ из переменной о версии Java не говорят
    private Optional<Dockerfiles.Instruction> runtimeImage(TextFile file) {
        Set<String> stages = new HashSet<>();
        Dockerfiles.Instruction last = null;
        for (Dockerfiles.Instruction instruction : Dockerfiles.instructions(file)) {
            if (!Dockerfiles.FROM.equals(instruction.keyword())) {
                continue;
            }
            last = instruction;
            String[] words = SPACES.split(instruction.arguments());
            int imageIndex = words[0].startsWith(FLAG_PREFIX) && words.length > 1 ? 1 : 0;
            if (words.length > imageIndex + 2 && AS.equalsIgnoreCase(words[imageIndex + 1])) {
                stages.add(words[imageIndex + 2].toLowerCase(Locale.ROOT));
            }
        }
        if (last == null) {
            return Optional.empty();
        }
        String image = imageOf(last);
        boolean known = !image.contains(VARIABLE) && !stages.contains(image.toLowerCase(Locale.ROOT));
        return known ? Optional.of(last) : Optional.empty();
    }

    private String imageOf(Dockerfiles.Instruction from) {
        String[] words = SPACES.split(from.arguments());
        return words[words[0].startsWith(FLAG_PREFIX) && words.length > 1 ? 1 : 0];
    }

    private OptionalInt javaOf(String image) {
        int digest = image.indexOf('@');
        String plain = digest < 0 ? image : image.substring(0, digest);
        int colon = plain.lastIndexOf(':');
        boolean tagged = colon > plain.lastIndexOf('/');
        String name = tagged ? plain.substring(0, colon) : plain;
        Matcher inName = NAME_VERSION.matcher(name);
        if (inName.find()) {
            return OptionalInt.of(Integer.parseInt(inName.group(2)));
        }
        if (!tagged || !JAVA_IMAGE.matcher(name).find()) {
            return OptionalInt.empty();
        }
        Matcher inTag = TAG_VERSION.matcher(plain.substring(colon + 1));
        return inTag.find() ? OptionalInt.of(Integer.parseInt(inTag.group(2))) : OptionalInt.empty();
    }

    // Версия из файла сборки рядом с Dockerfile: он упаковывает модуль, в каталоге которого лежит. Если рядом
    // файла сборки нет, а в проекте он один, берется он
    private OptionalInt builtFor(TextFile dockerfile, List<TextFile> files) {
        Path directory = dockerfile.path().toAbsolutePath().normalize().getParent();
        List<TextFile> builds = files.stream()
                .filter(file -> TextFiles.isPom(file) || TextFiles.isGradle(file))
                .toList();
        List<TextFile> beside = builds.stream()
                .filter(file -> file.path().toAbsolutePath().normalize().getParent().equals(directory))
                .toList();
        if (!beside.isEmpty()) {
            return JavaVersions.lowest(beside);
        }
        return builds.size() == 1 ? JavaVersions.lowest(builds) : OptionalInt.empty();
    }
}
