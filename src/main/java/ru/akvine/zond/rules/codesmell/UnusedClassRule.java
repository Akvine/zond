package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractUnusedDeclarationRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.ProjectUsages;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class UnusedClassRule extends AbstractUnusedDeclarationRule {
    private static final int MAX_NAMED = 3;
    private static final Pattern TEST_SUPPORT_NAME = Pattern.compile(
            ".*(Test|Mock|Fake|Stub|Fixture|Dummy|Sample|Example|Demo|Benchmark|InMemory).*");
    private static final Pattern TEST_SUPPORT_PATH = Pattern.compile(
            "(?i)/(test|tests|testing|testkit|testsupport|test-support|testutil|testutils|fixtures?|mocks?|samples?"
                    + "|examples?|demo|benchmarks?)[^/]*/");

    @Override
    public String code() {
        return RuleCodes.UNUSED_CLASS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет классы, на которые нигде нет ссылок либо ссылаются только такие же"
                + " неиспользуемые классы или одни тесты, и бины, которые никто не использует";
    }

    @Override
    protected void check(
            SourceFile sourceFile, TypeDeclaration<?> type, ProjectUsages usages, List<Violation> violations) {
        if (!usages.isTypeDead(type)) {
            checkLive(sourceFile, type, usages, violations);
            return;
        }
        if (isInsideDead(type, usages)) {
            return;
        }

        // Класс, на который ссылается только мертвый код, сам мертв: удалять их нужно вместе
        Set<String> users = usages.deadUsersOf(type);
        String reason = users.isEmpty()
                ? "нигде в проекте не используется"
                : "используется только в неиспользуемых классах (" + String.join(", ", users) + ")";
        violations.add(violation(sourceFile, type,
                "Класс '" + type.getNameAsString() + "' " + reason + ": мертвый код"
                        + " приходится читать и сопровождать впустую; удалите его. Если им пользуются извне"
                        + " (другой модуль, рефлексия, файл настроек), скройте находку комментарием zond:ignore")
                // Открытый класс модуля без точки входа - API, которым могут пользоваться снаружи проекта
                .withConfidence(usages.isModuleApi(type) ? Confidence.SUSPICION : null));
    }

    // Класс формально жив, но приложению не нужен: его создает контейнер либо на него ссылаются одни тесты
    private void checkLive(
            SourceFile sourceFile, TypeDeclaration<?> type, ProjectUsages usages, List<Violation> violations) {
        if (usages.isIdleBean(type)) {
            violations.add(violation(sourceFile, type,
                    "Бин '" + type.getNameAsString() + "' создается контейнером, но никем не используется: его не"
                            + " внедряют, не получают по имени, и у него нет методов, которые Spring вызывает сам;"
                            + " удалите класс. Если бин берут по имени из настроек или из другого модуля, скройте"
                            + " находку комментарием zond:ignore")
                    .withConfidence(Confidence.PROBABLE));
            return;
        }
        // О группе классов, нужных только тестам, сообщаем один раз - на ее вершине
        Optional<Set<String>> cluster = usages.testOnlyCluster(type);
        if (cluster.isEmpty() || isTestSupport(sourceFile, type)) {
            return;
        }
        // Жив только потому, что его имя встретилось в строке теста: использованием это не назвать
        Set<String> tests = usages.testUsersOf(type);
        if (tests.isEmpty()) {
            return;
        }
        String users = " (из " + String.join(", ", tests.stream().limit(MAX_NAMED).toList())
                + (tests.size() > MAX_NAMED ? " и еще " + (tests.size() - MAX_NAMED) : "") + ")";
        String pulled = cluster.get().isEmpty()
                ? ""
                : " Через него только тестам нужны и другие классы (" + cluster.get().size() + "): "
                + String.join(", ", cluster.get().stream().limit(MAX_NAMED).toList())
                + (cluster.get().size() > MAX_NAMED ? " и другие." : ".");
        violations.add(violation(sourceFile, type,
                "Класс '" + type.getNameAsString() + "' используется только в тестах" + users + ": в самом"
                        + " приложении на него нет ссылок - код написан и проверен, но не подключен." + pulled
                        + " Подключите его либо удалите вместе с тестами")
                .withConfidence(Confidence.PROBABLE));
    }

    // Заготовки для тестов, которые лежат рядом с кодом приложения намеренно: MockClock, test/fixtures
    private boolean isTestSupport(SourceFile sourceFile, TypeDeclaration<?> type) {
        return TEST_SUPPORT_NAME.matcher(type.getNameAsString()).matches()
                || TEST_SUPPORT_PATH.matcher(sourceFile.path().toString().replace('\\', '/')).find();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }
}
