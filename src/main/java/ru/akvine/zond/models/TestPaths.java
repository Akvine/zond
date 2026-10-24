package ru.akvine.zond.models;

import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Пути тестового кода и тестовой обвязки: src/test, ci/tests, e2e, integrationTest. Настройки, образы и
 * docker-compose из таких каталогов в рабочую среду не попадают: пароли в них - учебные, образы - для стенда
 */
public final class TestPaths {
    private static final Set<String> TEST_DIRECTORIES = Set.of(
            "test", "tests", "javatests", "it", "e2e", "integrationTest", "integration-test", "integration-tests",
            "testFixtures");

    // Каталоги CI: образы и docker-compose в .github/workflows/cluster-test поднимают окружение для прогона
    // тестов в конвейере. Сами файлы конвейера проверяют правила CI - они смотрят на тип файла, а не на путь
    private static final Set<String> CI_DIRECTORIES = Set.of(".github", ".gitlab", ".circleci");

    // Наборы исходников Gradle рядом с src/test: src/internalClusterTest, src/javaRestTest, src/heapPressureTest
    private static final Pattern TEST_SOURCE_SET = Pattern.compile("[a-z][A-Za-z0-9]*(Test|Tests|TestFixtures)");
    private static final String SOURCE_DIRECTORY = "src";

    private TestPaths() {
    }

    public static boolean isTest(Path path) {
        String previous = "";
        for (Path part : path) {
            String name = part.toString();
            if (TEST_DIRECTORIES.contains(name) || CI_DIRECTORIES.contains(name) || isTestSourceSet(previous, name)) {
                return true;
            }
            previous = name;
        }
        return false;
    }

    /**
     * @return true для набора исходников с тестами: src/integTest, src/yamlRestTest. Сам src/main и каталоги
     * с таким именем глубже (пакет com.shop.loadTest) не подходят
     */
    public static boolean isTestSourceSet(String parent, String name) {
        return SOURCE_DIRECTORY.equals(parent) && TEST_SOURCE_SET.matcher(name).matches();
    }
}
