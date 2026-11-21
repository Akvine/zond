package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
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
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.BeanScopes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ResourceProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class WeakCipherRule extends AbstractRule {
    private static final String CIPHER = "Cipher";
    private static final String GET_INSTANCE = "getInstance";
    // Где у параметров шифра стоит вектор инициализации: new IvParameterSpec(iv), new GCMParameterSpec(128, iv),
    // new ChaCha20ParameterSpec(nonce, counter), у BouncyCastle - new ParametersWithIV(key, iv)
    private static final Map<String, Integer> IV_POSITIONS = Map.of(
            "IvParameterSpec", 0, "GCMParameterSpec", 1, "ChaCha20ParameterSpec", 0, "ParametersWithIV", 1,
            "AEADParameters", 2);
    private static final Set<String> INIT_ANNOTATIONS = Set.of("PostConstruct");
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

        for (MethodCallExpr call : sourceFile.findAll(MethodCallExpr.class)) {
            if (!MethodCalls.isCallOn(call, CIPHER, GET_INSTANCE) || call.getArguments().isEmpty()) {
                continue;
            }
            // Cipher.getInstance("DES/ECB/PKCS5Padding"), переменная с таким значением или настройка из файла
            ResourceProperties.textOf(call.getArgument(0))
                    .flatMap(this::describeProblem)
                    .ifPresent(problem -> violations.add(violation(sourceFile, call,
                            "'" + call + "': " + problem + "; используйте AES/GCM/NoPadding со случайным IV")));
        }

        for (ObjectCreationExpr creation : sourceFile.findAll(ObjectCreationExpr.class)) {
            Integer position = IV_POSITIONS.get(creation.getType().getNameAsString());
            if (position == null || creation.getArguments().size() <= position) {
                continue;
            }
            Expression vector = Nodes.unwrap(creation.getArgument(position));
            if (isConstant(vector)) {
                violations.add(violation(sourceFile, creation,
                        "'" + creation + "': вектор инициализации постоянный - одинаковые данные шифруются в"
                                + " одинаковый результат, что раскрывает их структуру; генерируйте IV через"
                                + " SecureRandom для каждого сообщения"));
                continue;
            }
            sharedField(vector).ifPresent(field -> violations.add(violation(sourceFile, creation,
                    "'" + creation + "': вектор инициализации '" + field + "' лежит в поле бина и задается один"
                            + " раз - он один на все сообщения. Одинаковые данные шифруются в одинаковый"
                            + " результат, а у потоковых шифров и режимов CTR и GCM повтор IV с тем же ключом"
                            + " раскрывает и сами данные; генерируйте IV через SecureRandom для каждого сообщения"
                            + " и храните его рядом с шифртекстом")));
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
    private boolean isConstant(Expression value) {
        if (value.isNameExpr()) {
            return CONSTANT_NAME.matcher(value.asNameExpr().getNameAsString()).matches();
        }
        if (value.isArrayCreationExpr()) {
            return true;
        }
        return value.isMethodCallExpr()
                && value.asMethodCallExpr().getScope().filter(Expression::isStringLiteralExpr).isPresent();
    }

    /**
     * Вектор из поля бина-одиночки, которое заполняют только при создании: конструктор, инициализатор,
     * {@code @PostConstruct}, внедрение через {@code @Value}. Такой вектор один на все вызовы. Поле объекта,
     * который создают на одно сообщение, сюда не входит: там вектор может быть случайным
     *
     * @return имя поля
     */
    private Optional<String> sharedField(Expression vector) {
        String name = vector.isNameExpr() ? vector.asNameExpr().getNameAsString()
                : vector.isFieldAccessExpr() && vector.asFieldAccessExpr().getScope().isThisExpr()
                ? vector.asFieldAccessExpr().getNameAsString() : null;
        if (name == null || vector.isNameExpr() && LocalTypes.isLocal(vector, name)) {
            return Optional.empty();
        }
        Optional<VariableDeclarator> field = LocalTypes.findField(vector, name);
        Optional<ClassOrInterfaceDeclaration> owner = field
                .flatMap(variable -> variable.findAncestor(ClassOrInterfaceDeclaration.class));
        if (owner.isEmpty() || !BeanScopes.isSingletonBean(owner.get())) {
            return Optional.empty();
        }
        boolean onlyAtCreation = owner.get().findAll(AssignExpr.class).stream()
                .filter(assignment -> isField(assignment.getTarget(), name))
                .allMatch(this::isAtCreation);
        return onlyAtCreation ? Optional.of(name) : Optional.empty();
    }

    private boolean isField(Expression target, String name) {
        return target.isNameExpr() && target.asNameExpr().getNameAsString().equals(name)
                && !LocalTypes.isLocal(target, name)
                || target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()
                && target.asFieldAccessExpr().getNameAsString().equals(name);
    }

    private boolean isAtCreation(Node assignment) {
        return assignment.findAncestor(ConstructorDeclaration.class).isPresent()
                || assignment.findAncestor(InitializerDeclaration.class).isPresent()
                || assignment.findAncestor(MethodDeclaration.class)
                .filter(method -> Annotations.hasAny(method, INIT_ANNOTATIONS))
                .isPresent();
    }
}
