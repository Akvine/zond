package ru.akvine.zond.rules.resources;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ConfigKeys;
import ru.akvine.zond.rules.files.TextFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GracefulShutdownMissingRule extends AbstractContextRule {
    private static final String SHUTDOWN = "server.shutdown";
    private static final String GRACEFUL = "graceful";
    private static final String IMMEDIATE = "immediate";
    private static final String WEB_STARTER = "spring-boot-starter-web";
    private static final String APPLICATION = "application";
    private static final String CONSEQUENCE = " при выкладке и перезапуске запросы, которые выполняются в этот момент,"
            + " обрываются, и клиенты получают ошибки;";

    // <artifactId>spring-boot-starter-parent</artifactId> ... <version>3.2.1</version>
    private static final Pattern PARENT = Pattern.compile("spring-boot-starter-parent");
    private static final Pattern MAVEN_VERSION = Pattern.compile("<version>\\s*(\\d+)\\.(\\d+)[^<]*</version>");
    // id 'org.springframework.boot' version '3.2.1'
    private static final Pattern GRADLE_PLUGIN = Pattern.compile(
            "org\\.springframework\\.boot['\")]*\\s+version\\s+['\"](\\d+)\\.(\\d+)");
    private static final int VERSION_SEARCH_LINES = 4;

    // С версии 3.4 плавная остановка включена по умолчанию
    private static final int GRACEFUL_BY_DEFAULT_MAJOR = 3;
    private static final int GRACEFUL_BY_DEFAULT_MINOR = 4;

    /**
     * Где в файле сборки объявлена версия Spring Boot
     */
    private record BootVersion(TextFile file, int line, int major, int minor) {

        boolean isGracefulByDefault() {
            return major > GRACEFUL_BY_DEFAULT_MAJOR
                    || major == GRACEFUL_BY_DEFAULT_MAJOR && minor >= GRACEFUL_BY_DEFAULT_MINOR;
        }
    }

    @Override
    public String code() {
        return RuleCodes.GRACEFUL_SHUTDOWN_MISSING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет настройки и файлы сборки и ищет веб-приложение без плавной остановки (server.shutdown=graceful)";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        boolean graceful = false;
        for (ConfigFile file : context.configFiles()) {
            Optional<ConfigProperty> shutdown = file.find(SHUTDOWN);
            if (shutdown.isEmpty() || file.isNonProduction()) {
                continue;
            }
            graceful |= GRACEFUL.equalsIgnoreCase(shutdown.get().value().trim());
            if (IMMEDIATE.equalsIgnoreCase(shutdown.get().value().trim())) {
                violations.add(violation(file.path(), shutdown.get().line(),
                        "Плавная остановка отключена (" + SHUTDOWN + "=immediate):" + CONSEQUENCE
                                + " задайте " + SHUTDOWN + "=graceful").withConfidence(Confidence.CONFIRMED));
            }
        }
        if (graceful || !violations.isEmpty() || !isWebApplication(context)) {
            return violations;
        }

        // Без версии неизвестно, какое значение действует по умолчанию: молчим
        Optional<BootVersion> version = findVersion(context).filter(found -> !found.isGracefulByDefault());
        version.ifPresent(found -> {
            Optional<ConfigFile> main = context.configFiles().stream()
                    .filter(file -> APPLICATION.equals(ConfigKeys.baseName(file.path()))
                            && ConfigKeys.profile(file.path()).isEmpty() && !file.isNonProduction())
                    .findFirst();
            String message = "Веб-приложение на Spring Boot " + found.major() + "." + found.minor()
                    + " без плавной остановки: по умолчанию действует immediate," + CONSEQUENCE
                    + " задайте " + SHUTDOWN + "=graceful и spring.lifecycle.timeout-per-shutdown-phase";
            violations.add(main.map(file -> violation(file.path(), message))
                    .orElseGet(() -> violation(found.file().path(), found.line(), message)));
        });
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    private boolean isWebApplication(ScanContext context) {
        return context.textFiles().stream()
                .filter(file -> TextFiles.isPom(file) || TextFiles.isGradle(file))
                .flatMap(file -> file.lines().stream())
                .anyMatch(line -> line.contains(WEB_STARTER));
    }

    private Optional<BootVersion> findVersion(ScanContext context) {
        for (TextFile file : context.textFiles()) {
            boolean pom = TextFiles.isPom(file);
            if (!pom && !TextFiles.isGradle(file)) {
                continue;
            }
            for (int index = 0; index < file.lines().size(); index++) {
                String line = file.lines().get(index);
                Matcher plugin = GRADLE_PLUGIN.matcher(line);
                if (!pom && plugin.find()) {
                    return Optional.of(version(file, index, plugin));
                }
                if (!pom || !PARENT.matcher(line).find()) {
                    continue;
                }
                // Версия родителя стоит в той же строке либо в нескольких следующих
                for (int next = index; next < Math.min(file.lines().size(), index + VERSION_SEARCH_LINES); next++) {
                    Matcher version = MAVEN_VERSION.matcher(file.lines().get(next));
                    if (version.find()) {
                        return Optional.of(version(file, next, version));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private BootVersion version(TextFile file, int index, Matcher matcher) {
        return new BootVersion(file, index + 1, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }
}
