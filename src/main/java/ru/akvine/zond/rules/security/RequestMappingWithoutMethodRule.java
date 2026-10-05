package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Mappings;

import java.util.List;

@Component
public class RequestMappingWithoutMethodRule extends AbstractRule {
    @Override
    public String code() {
        return RuleCodes.REQUEST_MAPPING_WITHOUT_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @RequestMapping на методе без указания HTTP-метода";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Mappings.find(method)
                        .filter(mapping -> Mappings.isRequestMapping(mapping) && Mappings.httpMethod(mapping).isEmpty())
                        .isPresent())
                .map(method -> violation(sourceFile, method,
                        "@RequestMapping на методе '" + method.getNameAsString() + "' без HTTP-метода: обработчик"
                                + " отвечает и на GET, и на POST, и на DELETE - действие можно вызвать ссылкой"
                                + " или в обход защиты от CSRF; используйте @GetMapping, @PostMapping и подобные"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }
}
