package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * new RestTemplate(), HttpClient.newHttpClient(), WebClient.create(...) в обычном методе сервиса: на каждый
 * вызов создается новый клиент со своим пулом соединений. Соединения не переиспользуются - каждый запрос заново
 * открывает TCP и проходит TLS, а брошенные пулы держат сокеты и потоки, пока их не соберет сборщик мусора.
 * Создание в цикле находит jr:419, здесь - один раз на вызов метода
 */
@Component
public class HttpClientPerCallRule extends AbstractRule {
    private static final Set<String> CLIENT_CLASSES = Set.of("RestTemplate", "OkHttpClient", "DefaultHttpClient");
    // HttpClient.newHttpClient(), HttpClients.createDefault(), WebClient.create(url), RestClient.create()
    private static final Set<String> CLIENT_FACTORIES = Set.of(
            "HttpClient.newHttpClient", "HttpClients.createDefault", "HttpClients.createSystem",
            "HttpClients.createMinimal", "WebClient.create", "RestClient.create", "HttpClientBuilder.create");
    // ...build() у построителя клиента
    private static final List<String> CLIENT_BUILDERS = List.of(
            "HttpClient.newBuilder", "HttpClients.custom", "HttpClientBuilder.create", "WebClient.builder",
            "RestClient.builder", "new OkHttpClient.Builder", "HttpAsyncClients.custom");
    // Откуда берутся HTTP-клиенты с пулом соединений
    private static final List<String> CLIENT_PACKAGES = List.of(
            "org.springframework.web", "org.springframework.boot", "java.net.http", "org.apache.http", "org.apache.hc",
            "okhttp3", "com.squareup.okhttp", "io.vertx");
    // Первое имя выражения: RestClient в RestClient.create(url), OkHttpClient в new OkHttpClient.Builder()
    private static final Pattern LEADING_TYPE = Pattern.compile("^(?:new )?([A-Za-z_$][\\w$]*)");
    private static final String BUILD = "build";
    private static final String MAIN = "main";

    @Override
    public String code() {
        return RuleCodes.HTTP_CLIENT_PER_CALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет RestTemplate, HttpClient, WebClient и OkHttpClient, которые создаются при"
                + " каждом вызове метода, а не один раз";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Expression> creations = new ArrayList<>();
        sourceFile.unit().findAll(ObjectCreationExpr.class).stream()
                .filter(creation -> CLIENT_CLASSES.contains(creation.getType().getNameAsString())
                        && creation.getAnonymousClassBody().isEmpty())
                .forEach(creations::add);
        sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(this::isClientFactoryCall)
                .forEach(creations::add);
        return creations.stream()
                .filter(creation -> !isOtherLibraryClass(creation))
                .filter(this::isCreatedPerCall)
                .map(creation -> violation(sourceFile, creation,
                        "'" + Nodes.text(creation) + "' создает новый HTTP-клиент при каждом вызове метода: пул"
                                + " соединений не переиспользуется, каждый запрос заново открывает соединение и"
                                + " проходит TLS, а брошенные клиенты держат сокеты; создайте клиент один раз -"
                                + " полем или бином - и используйте его")
                        .withConfidence(isClosedAfterUse(creation) ? Confidence.SUSPICION : null))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private boolean isClientFactoryCall(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return false;
        }
        if (CLIENT_FACTORIES.contains(scope.get() + "." + call.getNameAsString())
                && !(call.getParentNode().orElse(null) instanceof MethodCallExpr)) {
            return true;
        }
        if (!BUILD.equals(call.getNameAsString())) {
            return false;
        }
        String chain = scope.get().toString();
        return CLIENT_BUILDERS.stream().anyMatch(chain::startsWith);
    }

    // RestClient.create(url) у Hazelcast - своя обертка над HttpURLConnection без пула, а не RestClient из Spring
    private boolean isOtherLibraryClass(Expression creation) {
        Matcher head = LEADING_TYPE.matcher(creation.toString());
        if (!head.find()) {
            return false;
        }
        String type = head.group(1);
        Optional<CompilationUnit> unit = creation.findCompilationUnit();
        boolean declaredHere = unit.stream()
                .flatMap(file -> file.getTypes().stream())
                .anyMatch(declaration -> declaration.getNameAsString().equals(type));
        return declaredHere || unit.stream()
                .flatMap(file -> file.getImports().stream())
                .filter(declaration -> !declaration.isAsterisk() && !declaration.isStatic())
                .map(ImportDeclaration::getNameAsString)
                .filter(name -> name.endsWith("." + type))
                .anyMatch(name -> CLIENT_PACKAGES.stream().noneMatch(name::startsWith));
    }

    // try (CloseableHttpClient client = HttpClients.createDefault()): сокеты не утекают, теряется только пул
    private boolean isClosedAfterUse(Expression creation) {
        return creation.getParentNode()
                .filter(VariableDeclarator.class::isInstance)
                .flatMap(Node::getParentNode)
                .flatMap(Node::getParentNode)
                .filter(TryStmt.class::isInstance)
                .isPresent();
    }

    private boolean isCreatedPerCall(Expression creation) {
        Optional<Node> callable = Nodes.enclosingCallable(creation);
        if (callable.isEmpty() || !(callable.get() instanceof MethodDeclaration method)) {
            // Поле, конструктор, блок инициализации - создается один раз вместе с объектом
            return false;
        }
        boolean once = MAIN.equals(method.getNameAsString()) && method.isStatic()
                || CodeContexts.isStartup(creation);
        return !once && Nodes.enclosingLoop(creation).isEmpty() && !TestClasses.isInside(creation)
                && !isStoredOrReturned(creation, method);
    }

    // this.client = new RestTemplate() (ленивое создание), return new RestTemplate() (фабрика): клиент живет дольше
    private boolean isStoredOrReturned(Expression creation, MethodDeclaration method) {
        Node parent = creation.getParentNode().orElse(null);
        if (parent instanceof ReturnStmt) {
            return true;
        }
        if (parent instanceof AssignExpr assignment && assignment.getValue() == creation) {
            Expression target = assignment.getTarget();
            return target.isFieldAccessExpr() || target.isNameExpr()
                    && LocalTypes.findDeclaration(target).filter(declaration -> declaration.findAncestor(
                    FieldDeclaration.class).isPresent()).isPresent();
        }
        if (parent instanceof VariableDeclarator variable) {
            String name = variable.getNameAsString();
            return method.findAll(ReturnStmt.class).stream()
                    .flatMap(statement -> statement.getExpression().stream())
                    .anyMatch(value -> value instanceof NameExpr returned && returned.getNameAsString().equals(name));
        }
        // Методу-обертке передали клиент: new Service(new RestTemplate()) - его жизнь решает получатель
        return parent instanceof ObjectCreationExpr;
    }
}
