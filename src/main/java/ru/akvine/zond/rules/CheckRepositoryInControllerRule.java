package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckRepositoryInControllerRule extends AbstractRule {
    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("Controller", "RestController");

    private static final String REPOSITORY = "Repository";

    // Репозиторий узнаем по имени типа либо его предка: interface Users extends JpaRepository
    private static final Pattern REPOSITORY_TYPE =
            Pattern.compile(".*(Repository|Dao|DAO)$|^EntityManager$|^JdbcTemplate$");

    @Override
    public String code() {
        return RuleCodes.CHECK_REPOSITORY_IN_CONTROLLER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет контроллеры, которые работают с репозиторием напрямую";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!Annotations.hasAny(type, CONTROLLER_ANNOTATIONS)) {
                continue;
            }
            SpringBeans.findDependencies(type).stream()
                    .filter(this::isRepository)
                    .map(SpringBeans.Dependency::type)
                    .findFirst()
                    .ifPresent(repository -> violations.add(violation(sourceFile, type,
                            "Контроллер '" + type.getNameAsString() + "' зависит от '" + repository + "' напрямую:"
                                    + " бизнес-логика и границы транзакций оказываются в веб-слое, их нельзя"
                                    + " переиспользовать и трудно тестировать; вынесите работу с данными в сервис")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private boolean isRepository(SpringBeans.Dependency dependency) {
        Type type = dependency.declaredType();
        boolean annotated = Types.projectTypeAnnotations(type)
                .filter(annotations -> annotations.contains(REPOSITORY))
                .isPresent();
        return annotated || Types.matches(type, name -> REPOSITORY_TYPE.matcher(name).matches())
                .orElseGet(() -> REPOSITORY_TYPE.matcher(dependency.type()).matches());
    }
}
