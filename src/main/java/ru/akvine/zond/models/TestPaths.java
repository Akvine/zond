package ru.akvine.zond.models;

import java.nio.file.Path;
import java.util.Set;

/**
 * Пути тестового кода и тестовой обвязки: src/test, ci/tests, e2e, integrationTest. Настройки, образы и
 * docker-compose из таких каталогов в рабочую среду не попадают: пароли в них - учебные, образы - для стенда
 */
public final class TestPaths {
    private static final Set<String> TEST_DIRECTORIES = Set.of(
            "test", "tests", "it", "e2e", "integrationTest", "integration-test", "integration-tests", "testFixtures");

    // Каталоги CI: образы и docker-compose в .github/workflows/cluster-test поднимают окружение для прогона
    // тестов в конвейере. Сами файлы конвейера проверяют правила CI - они смотрят на тип файла, а не на путь
    private static final Set<String> CI_DIRECTORIES = Set.of(".github", ".gitlab", ".circleci");

    private TestPaths() {
    }

    public static boolean isTest(Path path) {
        for (Path part : path) {
            if (TEST_DIRECTORIES.contains(part.toString()) || CI_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
