package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DuplicateCodeRule extends AbstractRule implements ProjectRule {
    private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter(new DefaultPrinterConfiguration()
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_COMMENTS)));

    private static final RuleParameter MIN_LINES =
            new RuleParameter("min-lines", 10, "Тела короче этого числа строк копиями не считаются");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MIN_LINES);
    }

    @Override
    public String code() {
        return RuleCodes.DUPLICATE_CODE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет методы с одинаковым телом";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        // Текст тела без комментариев -> первый метод с таким телом
        Map<String, String> known = new HashMap<>();
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (method.getBody().isEmpty() || TestClasses.isInside(method)) {
                    continue;
                }
                String body = normalize(method.getBody().get());
                if (body.lines().count() < value(MIN_LINES)) {
                    continue;
                }
                String name = ownerOf(method) + method.getNameAsString();
                String first = known.putIfAbsent(body, name);
                if (first != null) {
                    violations.add(violation(sourceFile, method,
                            "Тело метода '" + name + "' дословно повторяет '" + first + "': исправление придется"
                                    + " вносить в обе копии, и одну из них забудут; вынесите общий код в один метод"));
                }
            }
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

    // Копии с разными комментариями и отступами - все равно копии: печатаем тело заново и без комментариев
    private String normalize(BlockStmt body) {
        return PRINTER.print(body);
    }

    private String ownerOf(MethodDeclaration method) {
        return method.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?>)
                .map(parent -> ((TypeDeclaration<?>) parent).getNameAsString() + ".")
                .orElse("");
    }
}
