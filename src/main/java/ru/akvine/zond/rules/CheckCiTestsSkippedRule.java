package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckCiTestsSkippedRule extends AbstractContextRule {
    // -DskipTests, -Dmaven.test.skip=true, -x test; -DskipTests=false пропуском не является
    private static final Pattern SKIP = Pattern.compile(
            "(?<![\\w-])(-DskipTests(?:=true)?|-Dmaven\\.test\\.skip(?:=true)?|-x\\s+test|--exclude-task\\s+test)(?![\\w=.])");

    // Команда Maven или Gradle с фазой, на которой выполняются тесты
    private static final Pattern BUILD = Pattern.compile(
            ".*\\b(mvn|mvnw|gradle|gradlew)\\b.*\\b(test|verify|check|build|install|package|deploy)\\b.*");

    @Override
    public String code() {
        return RuleCodes.CHECK_CI_TESTS_SKIPPED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы CI и ищет сборку с пропуском тестов, если тесты не запускаются ни в одной другой команде";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            List<CiFiles.Command> commands = CiFiles.commands(file);
            // Сборка без тестов допустима, когда тесты идут отдельной задачей
            boolean runsTests = commands.stream()
                    .anyMatch(command -> BUILD.matcher(command.text()).matches() && !SKIP.matcher(command.text()).find());
            if (runsTests) {
                continue;
            }
            for (CiFiles.Command command : commands) {
                Matcher skip = SKIP.matcher(command.text());
                if (skip.find()) {
                    violations.add(violation(file.path(), command.line(),
                            "Сборка в CI идет с '" + skip.group(1) + "', и тесты не запускаются ни в одной другой"
                                    + " команде этого файла: ошибку, которую поймал бы тест, увидят уже"
                                    + " пользователи; уберите пропуск либо добавьте отдельную задачу с тестами"));
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
}
