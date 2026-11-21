package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.BuildFiles;
import ru.akvine.zond.rules.files.SpringVersions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Библиотека собрана под другое поколение Spring Boot, чем само приложение. Между вторым и третьим поколением
 * сменились и пакеты (javax.* на jakarta.*), и способ, которым стартер подключает свою автонастройку, поэтому
 * стартер "не того" поколения либо не дает приложению запуститься, либо молча ничего не делает: зависимость
 * в сборке есть, а страницы документации или защиты вызовов нет.
 * <p>
 * Список встроенный и короткий: только библиотеки, для которых соответствие поколениям названо их же авторами
 */
@Component
public class LibraryForAnotherBootGenerationRule extends AbstractContextRule {
    private static final int JAKARTA_BOOT = 3;
    private static final Pattern MAJOR = Pattern.compile("^(\\d+)\\.");

    private static final String SPRINGDOC = "org.springdoc";
    private static final Set<String> SPRINGDOC_V1 = Set.of(
            "springdoc-openapi-ui", "springdoc-openapi-webflux-ui", "springdoc-openapi-webmvc-core",
            "springdoc-openapi-webflux-core");
    private static final String SPRINGDOC_V2_PREFIX = "springdoc-openapi-starter-";
    private static final String RESILIENCE = "io.github.resilience4j";
    private static final String RESILIENCE_BOOT2 = "resilience4j-spring-boot2";
    private static final String RESILIENCE_BOOT3 = "resilience4j-spring-boot3";
    private static final String SPRINGFOX = "io.springfox";
    private static final String MYBATIS = "org.mybatis.spring.boot";
    private static final String MYBATIS_STARTER = "mybatis-spring-boot-starter";
    // Поколения стартера MyBatis совпадают с поколениями Spring Boot: 2.x - для второго, 3.x - для третьего,
    // 4.x - для четвертого
    private static final Set<Integer> MYBATIS_GENERATIONS = Set.of(2, 3, 4);

    @Override
    public String code() {
        return RuleCodes.LIBRARY_FOR_ANOTHER_BOOT_GENERATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы сборки и ищет библиотеки, собранные под другое поколение Spring Boot";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        // В хранилище из нескольких приложений поколения могут различаться: тогда неясно, к какому зависимость
        Set<Integer> generations = SpringVersions.boot(context.textFiles()).stream()
                .map(SpringVersions.Version::major)
                .collect(Collectors.toSet());
        if (generations.size() != 1) {
            return List.of();
        }
        int boot = generations.iterator().next();
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            for (BuildFiles.Dependency dependency : BuildFiles.dependencies(file, context.folderFiles())) {
                if (dependency.managed() || dependency.isTestOnly()) {
                    continue;
                }
                problem(dependency, boot).ifPresent(problem -> violations.add(violation(file.path(), dependency.line(),
                        dependency.coordinates() + (dependency.version().isEmpty() ? "" : ":" + dependency.version())
                                + " в приложении на Spring Boot " + boot + ": " + problem)));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private Optional<String> problem(BuildFiles.Dependency dependency, int boot) {
        String artifact = dependency.artifact();
        boolean jakarta = boot >= JAKARTA_BOOT;
        if (SPRINGDOC.equals(dependency.group())) {
            if (jakarta && SPRINGDOC_V1.contains(artifact)) {
                return Optional.of("это springdoc-openapi первой версии, она рассчитана на Spring Boot 1 и 2 -"
                        + " автонастройка не подключится, и страницы документации не будет; для Spring Boot 3 нужен"
                        + " springdoc-openapi второй версии: " + SPRINGDOC_V2_PREFIX
                        + (artifact.contains("webflux") ? "webflux" : "webmvc")
                        + (artifact.endsWith("-ui") ? "-ui" : "-api"));
            }
            if (!jakarta && artifact.startsWith(SPRINGDOC_V2_PREFIX)) {
                return Optional.of("артефакты springdoc-openapi-starter-* появились во второй версии библиотеки,"
                        + " которой нужен Spring Boot 3; для Spring Boot 2 берите springdoc-openapi-ui версии 1.x");
            }
        }
        if (RESILIENCE.equals(dependency.group())) {
            if (jakarta && RESILIENCE_BOOT2.equals(artifact)) {
                return Optional.of("этот стартер написан для Spring Boot 2 - его автонастройка не подключится,"
                        + " и аннотации @RateLimiter, @CircuitBreaker, @Retry останутся без действия; подключите"
                        + " стартер Resilience4j для своего поколения Spring Boot (для третьего - "
                        + RESILIENCE_BOOT3 + ") и spring-boot-starter-aop");
            }
            if (!jakarta && RESILIENCE_BOOT3.equals(artifact)) {
                return Optional.of("этот стартер написан для Spring Boot 3; для Spring Boot 2 нужен "
                        + RESILIENCE_BOOT2);
            }
        }
        if (jakarta && SPRINGFOX.equals(dependency.group())) {
            return Optional.of("Springfox не обновляется с 2020 года и собран на javax.servlet - со Spring Boot 3"
                    + " он не работает; замените его на springdoc-openapi второй версии");
        }
        if (MYBATIS.equals(dependency.group()) && MYBATIS_STARTER.equals(artifact)) {
            Matcher major = MAJOR.matcher(dependency.version());
            int starter = major.find() ? Integer.parseInt(major.group(1)) : boot;
            if (starter != boot && MYBATIS_GENERATIONS.contains(starter) && MYBATIS_GENERATIONS.contains(boot)) {
                return Optional.of("стартер MyBatis версии " + starter + ".x рассчитан на Spring Boot " + starter
                        + " - с другим поколением он несовместим; возьмите " + MYBATIS_STARTER + " версии "
                        + boot + ".x");
            }
        }
        return Optional.empty();
    }
}
