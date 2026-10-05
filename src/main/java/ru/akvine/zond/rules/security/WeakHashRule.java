package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class WeakHashRule extends AbstractRule {
    // Имя алгоритма, переданное аргументом: MessageDigest.getInstance("MD5"), hash(input, "SHA-1").
    // Только в принятом написании заглавными: иначе под правило попадут обычные строки вроде "md5"
    private static final Pattern WEAK_ALGORITHM = Pattern.compile("^(MD2|MD4|MD5|SHA-?1)$");

    // Готовые методы библиотек: DigestUtils.md5Hex(...), Hashing.sha1()
    private static final Pattern SECURITY_CONTEXT = Pattern.compile(
            "password|passwd|secret|token|signature|credential|hmac|otp|session|api_?key|private_?key|auth",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BENIGN_CONTEXT = Pattern.compile(
            "checksum|etag|cache|dedup|duplicate|fingerprint|file|content|gravatar|avatar|bucket|shard|partition"
                    + "|idempot|revision|version|digestOf|contentHash|uniq",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> WEAK_HASH_METHODS = Set.of(
            "md5", "md5Hex", "md5DigestAsHex", "md2Hex", "sha1", "sha1Hex", "sha", "shaHex");

    @Override
    public String code() {
        return RuleCodes.WEAK_HASH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет устаревшие алгоритмы хэширования: MD5, SHA-1";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (StringLiteralExpr literal : sourceFile.unit().findAll(StringLiteralExpr.class)) {
            boolean isArgument = literal.getParentNode().filter(parent -> parent instanceof MethodCallExpr).isPresent();
            if (isArgument && WEAK_ALGORITHM.matcher(literal.asString()).matches()) {
                report(sourceFile, literal, literal.asString()).ifPresent(violations::add);
            }
        }

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (call.getScope().isPresent() && WEAK_HASH_METHODS.contains(call.getNameAsString())) {
                report(sourceFile, call, call.getNameAsString() + "(...)").ifPresent(violations::add);
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    // Для чего считается хеш, по коду не видно: для контрольной суммы файла MD5 годится
    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // Что считается хешем, видно по именам вокруг: переменной, метода, класса, аргументов.
    // Контрольная сумма файла, ключ кэша, ETag - не защита, для них MD5 годится: находки нет.
    // Пароль, токен, подпись - находка вероятна. Иначе назначение неизвестно: только подозрение
    private Optional<Violation> report(SourceFile sourceFile, Node node, String algorithm) {
        String context = contextOf(node);
        boolean isSecurity = SECURITY_CONTEXT.matcher(context).find();
        if (!isSecurity && BENIGN_CONTEXT.matcher(context).find()) {
            return Optional.empty();
        }
        return Optional.of(describe(sourceFile, node, algorithm)
                .withConfidence(isSecurity ? Confidence.PROBABLE : Confidence.SUSPICION));
    }

    // Имена вокруг вызова: оператор целиком, метод и класс, в которых он стоит
    private String contextOf(Node node) {
        StringBuilder context = new StringBuilder();
        node.findAncestor(Statement.class).ifPresent(statement -> context.append(statement).append(' '));
        node.findAncestor(FieldDeclaration.class).ifPresent(field -> context.append(field).append(' '));
        node.findAncestor(MethodDeclaration.class).ifPresent(method -> context.append(method.getDeclarationAsString()).append(" "));
        node.findAncestor(TypeDeclaration.class).ifPresent(type -> context.append(type.getNameAsString()));
        return context.toString();
    }

    private Violation describe(SourceFile sourceFile, Node node, String algorithm) {
        return violation(sourceFile, node,
                "Устаревший алгоритм хэширования " + algorithm + ": для него известны практические коллизии,"
                        + " для паролей, подписей и проверки целостности он непригоден; используйте SHA-256"
                        + " или выше, а для паролей - bcrypt / argon2");
    }
}
