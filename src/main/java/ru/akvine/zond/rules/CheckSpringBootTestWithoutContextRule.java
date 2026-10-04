package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckSpringBootTestWithoutContextRule extends AbstractRule {
    private static final String SPRING_BOOT_TEST = "SpringBootTest";

    // Тест с таким методом как раз проверяет, что контекст поднимается
    private static final String CONTEXT_LOADS = "contextLoads";

    @Override
    public String code() {
        return RuleCodes.CHECK_SPRING_BOOT_TEST_WITHOUT_CONTEXT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует тесты и ищет @SpringBootTest там, где из контекста ничего не берется";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> Annotations.has(type, SPRING_BOOT_TEST))
                .filter(this::takesNothingFromContext)
                .map(type -> violation(sourceFile, type,
                        "@SpringBootTest на '" + type.getNameAsString() + "', а из контекста тест ничего"
                                + " не берет: приложение поднимается целиком ради обычных проверок, и тест идет"
                                + " секунды вместо миллисекунд; уберите аннотацию"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Ни одного поля с аннотацией (@Autowired, @MockBean, @Value, @LocalServerPort), нет конструктора
    // с параметрами и нет предка, в котором все это могло бы быть
    private boolean takesNothingFromContext(ClassOrInterfaceDeclaration type) {
        return type.getExtendedTypes().isEmpty()
                && type.getFields().stream().allMatch(field -> field.getAnnotations().isEmpty())
                && type.getConstructors().stream().allMatch(constructor -> constructor.getParameters().isEmpty())
                && type.getMethods().stream().allMatch(method -> method.getParameters().isEmpty())
                && type.getMethods().stream().noneMatch(method -> CONTEXT_LOADS.equals(method.getNameAsString()));
    }
}
