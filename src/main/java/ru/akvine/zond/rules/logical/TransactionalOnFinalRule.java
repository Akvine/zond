package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.List;

@Component
public class TransactionalOnFinalRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.TRANSACTIONAL_ON_FINAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Transactional на final и static методах и в final классах";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface()) {
                continue;
            }

            // Приватные не учитываем: на них @Transactional не работает в принципе, это ловит отдельное правило
            List<MethodDeclaration> transactional = type.getMethods().stream()
                    .filter(method -> !method.isPrivate())
                    .filter(method -> TransactionalAnnotations.findEffective(method).isPresent())
                    .toList();

            // От final класса прокси не создать вообще - отдельные методы уже не важны
            if (type.isFinal()) {
                if (TransactionalAnnotations.isPresent(type) || !transactional.isEmpty()) {
                    violations.add(violation(sourceFile, type,
                            "@Transactional в final классе '" + type.getNameAsString() + "': Spring создает прокси"
                                    + " наследованием, от final класса это невозможно - приложение не стартует"
                                    + " либо транзакции не откроются; уберите final"));
                }
                continue;
            }

            for (MethodDeclaration method : transactional) {
                if (method.isFinal() || method.isStatic()) {
                    violations.add(violation(sourceFile, method,
                            "@Transactional на " + (method.isStatic() ? "static" : "final") + " методе '"
                                    + method.getNameAsString() + "': прокси не может переопределить такой метод,"
                                    + " транзакция не откроется; уберите модификатор"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
