package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ConfigKeys;
import ru.akvine.zond.rules.files.GitIgnore;
import ru.akvine.zond.rules.files.TextFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Настройка ссылается на файл из classpath, которого не будет на чистой копии проекта: файл закрыт
 * {@code .gitignore} либо его нет вовсе. У разработчика, на машине которого файл лежит, все работает, а сборка из
 * хранилища не находит его и не запускается. Хранилища ключей и сертификаты сюда не относятся: их и не должно
 * быть в хранилище кода
 */
@Component
public class ConfigRefersToUntrackedFileRule extends AbstractContextRule {
    // classpath:logging/logback-spring-local.xml; optional:classpath:... и classpath*: - не обязательный файл
    private static final Pattern CLASSPATH_FILE = Pattern.compile(
            "(?<![\\w:*])classpath:/?([\\w./\\-]+\\.(\\w+))");
    private static final String OPTIONAL = "optional:";
    private static final String PLACEHOLDER = "$";
    private static final Set<String> SECRET_EXTENSIONS = Set.of(
            "pfx", "p12", "jks", "pem", "key", "crt", "cer", "der", "keystore", "truststore", "bcfks");
    // Свойства, без файла которых приложение не поднимается: о пропавшем файле по ним можно судить
    private static final Set<String> REQUIRED_FILE_KEYS = Set.of(
            "logging.config", "spring.liquibase.change-log", "spring.config.import");
    // application.yml лежит в корне ресурсов либо в его каталоге config
    private static final String CONFIG_DIRECTORY = "config";
    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Override
    public String code() {
        return RuleCodes.CONFIG_REFERS_TO_UNTRACKED_FILE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет настройки с файлами проекта и ищет ссылку на файл classpath, которого нет в проекте или"
                + " который закрыт .gitignore";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        GitIgnore ignore = GitIgnore.of(context.root());
        List<Path> roots = resourceRoots(context);
        List<Violation> violations = new ArrayList<>();
        for (ConfigFile configFile : context.configFiles()) {
            if (configFile.isNonProduction() || resources(configFile).isEmpty()) {
                continue;
            }
            Path resources = resources(configFile).get();
            for (ConfigProperty property : configFile.properties()) {
                if (property.value().contains(OPTIONAL) || property.value().contains(PLACEHOLDER)
                        || configFile.isNonProduction(property)) {
                    continue;
                }
                Matcher reference = CLASSPATH_FILE.matcher(property.value());
                while (reference.find()) {
                    if (SECRET_EXTENSIONS.contains(reference.group(2).toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    Path file = resources.resolve(reference.group(1)).normalize();
                    // Без .git и .gitignore неизвестно, хранится ли папка в хранилище вообще
                    Confidence confidence = ignore.isRepository() ? Confidence.PROBABLE : Confidence.SUSPICION;
                    describe(property, reference.group(1), file, ignore, roots).ifPresent(problem -> violations
                            .add(violation(configFile.path(), property.line(), problem).withConfidence(confidence)));
                }
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

    private Optional<String> describe(ConfigProperty property, String reference, Path file, GitIgnore ignore,
                                      List<Path> roots) {
        String start = property.key() + " ссылается на '" + reference + "'";
        if (Files.isRegularFile(file)) {
            return ignore.isIgnored(file)
                    ? Optional.of(start + ", а этот файл закрыт .gitignore: в хранилище его нет, и на чистой копии"
                    + " проекта приложение файл не найдет - у того, кто собирает проект из хранилища, оно не"
                    + " запустится; храните файл в хранилище либо укажите тот, который в нем есть")
                    : Optional.empty();
        }
        // Файл лежит в ресурсах соседнего модуля: на classpath он попадет вместе с ним
        if (roots.stream().anyMatch(root -> Files.isRegularFile(root.resolve(reference)))) {
            return Optional.empty();
        }
        // Файла нет, хотя каталог для него в ресурсах есть: опечатка в имени либо файл не положили в хранилище.
        // О файле из корня classpath так судить нельзя: его может принести и библиотека
        boolean required = REQUIRED_FILE_KEYS.contains(ConfigKeys.normalize(property.key()));
        boolean ownDirectory = reference.contains("/") && file.getParent() != null
                && Files.isDirectory(file.getParent());
        return required && ownDirectory
                ? Optional.of(start + ", а такого файла в ресурсах проекта нет, хотя каталог для него есть:"
                + " приложение не запустится; положите файл в хранилище либо исправьте имя")
                : Optional.empty();
    }

    /**
     * @return корень ресурсов, от которого считается classpath: каталог самого файла настроек либо уровень выше
     * config. Пусто для файла вне src/main/resources: настройки, которые кладут рядом с собранным приложением
     * (deploy/application.yml), о ресурсах проекта ничего не говорят
     */
    private Optional<Path> resources(ConfigFile configFile) {
        Path directory = configFile.path().toAbsolutePath().normalize().getParent();
        if (directory == null) {
            return Optional.empty();
        }
        boolean nested = directory.getFileName() != null
                && CONFIG_DIRECTORY.equals(directory.getFileName().toString()) && directory.getParent() != null;
        return Optional.of(nested ? directory.getParent() : directory).filter(root -> root.endsWith(RESOURCES));
    }

    // Ресурсы всех модулей проверяемой папки: у каждого файла сборки они лежат в src/main/resources
    private List<Path> resourceRoots(ScanContext context) {
        return context.folderFiles().stream()
                .filter(file -> TextFiles.isPom(file) || TextFiles.isGradle(file))
                .map(file -> file.path().toAbsolutePath().normalize().getParent())
                .filter(Objects::nonNull)
                .map(directory -> directory.resolve(RESOURCES))
                .filter(Files::isDirectory)
                .distinct()
                .toList();
    }
}
