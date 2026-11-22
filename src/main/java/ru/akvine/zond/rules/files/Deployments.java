package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.TextFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Файлы, которые описывают запуск приложения: Dockerfile, docker-compose, манифесты Kubernetes и значения
 * чарта Helm. Параметры JVM в них записаны строками, и часть файлов (шаблоны Helm) разобрать как YAML нельзя,
 * поэтому такие правила читают их построчно
 */
@UtilityClass
public class Deployments {
    private static final String COMMENT = "#";
    // values-dev.yaml, docker-compose.local.yml, Dockerfile.debug, compose-test.yaml: стенд разработчика или тестов
    private static final Pattern DEVELOPMENT_NAME = Pattern.compile(
            "(^|.*[._-])(dev|develop|development|local|localhost|test|tests|tst|debug|sandbox|demo|example)"
                    + "([._-].*|$)");

    /**
     * Строка файла с номером
     *
     * @param number номер строки, с единицы
     */
    public record Line(int number, String text) {
    }

    public boolean isDeployment(TextFile file) {
        return TextFiles.isDockerfile(file) || TextFiles.isCompose(file) || TextFiles.isKubernetes(file)
                || TextFiles.isHelmValues(file);
    }

    /**
     * @return строки файла без закомментированных
     */
    public List<Line> lines(TextFile file) {
        List<Line> lines = new ArrayList<>();
        for (int index = 0; index < file.lines().size(); index++) {
            String text = file.lines().get(index);
            if (!text.trim().startsWith(COMMENT)) {
                lines.add(new Line(index + 1, text));
            }
        }
        return lines;
    }

    /**
     * @return true, если по имени файл относится к стенду разработчика или тестов: отладочные настройки там
     * уместны, а учетные данные обычно ненастоящие
     */
    public boolean isDevelopment(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int extension = name.lastIndexOf('.');
        // Dockerfile.dev: "расширение" здесь и есть название стенда
        String base = extension > 0 && !name.startsWith("dockerfile") ? name.substring(0, extension) : name;
        return DEVELOPMENT_NAME.matcher(base).matches();
    }
}
