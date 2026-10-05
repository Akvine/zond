package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class WeakCipherRule extends AbstractRule {
    private static final String CIPHER = "Cipher";
    private static final String GET_INSTANCE = "getInstance";
    private static final String IV_PARAMETER_SPEC = "IvParameterSpec";
    private static final String MODE_SEPARATOR = "/";
    private static final String ECB = "/ECB/";

    private static final Pattern BROKEN_ALGORITHM = Pattern.compile("^(DES|DESEDE|3DES|RC2|RC4|ARCFOUR|BLOWFISH)(/.*)?$");
    private static final Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Override
    public String code() {
        return RuleCodes.WEAK_CIPHER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет слабое шифрование: DES, режим ECB, постоянный вектор инициализации";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!MethodCalls.isCallOn(call, CIPHER, GET_INSTANCE) || call.getArguments().isEmpty()) {
                continue;
            }
            StringLiterals.textOf(call.getArgument(0))
                    .flatMap(this::describeProblem)
                    .ifPresent(problem -> violations.add(violation(sourceFile, call,
                            "'" + call + "': " + problem + "; используйте AES/GCM/NoPadding со случайным IV")));
        }

        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (IV_PARAMETER_SPEC.equals(creation.getType().getNameAsString())
                    && !creation.getArguments().isEmpty()
                    && isConstant(creation.getArgument(0))) {
                violations.add(violation(sourceFile, creation,
                        "'" + creation + "': вектор инициализации постоянный - одинаковые данные шифруются в"
                                + " одинаковый результат, что раскрывает их структуру; генерируйте IV через"
                                + " SecureRandom для каждого сообщения"));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private Optional<String> describeProblem(String transformation) {
        String value = transformation.toUpperCase();
        if (BROKEN_ALGORITHM.matcher(value).matches()) {
            return Optional.of("алгоритм устарел и взламывается перебором");
        }
        if (value.contains(ECB)) {
            return Optional.of("режим ECB шифрует одинаковые блоки одинаково и раскрывает структуру данных");
        }
        // Cipher.getInstance("AES") без режима - это AES/ECB/PKCS5Padding
        return value.contains(MODE_SEPARATOR)
                ? Optional.empty()
                : Optional.of("режим не указан, по умолчанию используется ECB, который раскрывает структуру данных");
    }

    // new IvParameterSpec(IV), new IvParameterSpec("1234567890123456".getBytes()), new IvParameterSpec(new byte[16])
    private boolean isConstant(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isNameExpr()) {
            return CONSTANT_NAME.matcher(value.asNameExpr().getNameAsString()).matches();
        }
        if (value.isArrayCreationExpr()) {
            return true;
        }
        return value.isMethodCallExpr()
                && value.asMethodCallExpr().getScope().filter(Expression::isStringLiteralExpr).isPresent();
    }
}
