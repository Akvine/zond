package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.exceptions.GenericExceptionRule;
import ru.akvine.zond.rules.logical.ModifyingWithoutTransactionalRule;
import ru.akvine.zond.rules.logical.MultipleWritesWithoutTransactionRule;
import ru.akvine.zond.rules.security.RequestBodyWithoutValidRule;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Branches;
import ru.akvine.zond.rules.support.Mappings;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Приемы промышленного кода на Spring, которые правила обязаны понимать одинаково: составные аннотации,
 * транзакция от интерфейса или предка, транзакция через TransactionTemplate, ветки, которые вместе не выполняются
 */
class IndustrialIdiomsTest {

    @Test
    void composedAnnotationCountsAsItsParts() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("UseCase.java", """
                package app.common;
                @Deprecated
                @Component
                public @interface UseCase {
                }
                """);
        files.put("TransactionalService.java", """
                package app.common;
                @Service
                @Transactional(readOnly = true)
                public @interface TransactionalService {
                }
                """);
        files.put("SendMoney.java", """
                package app.money;
                import app.common.UseCase;
                @UseCase
                class SendMoney {
                }
                """);
        files.put("Reports.java", """
                package app.reports;
                import app.common.*;
                @TransactionalService
                class Reports {
                    public void build() {
                    }
                }
                """);
        files.put("Foreign.java", """
                package app.foreign;
                import org.library.UseCase;
                @UseCase
                class Foreign {
                }
                """);
        List<SourceFile> project = RuleTests.project(files);

        ClassOrInterfaceDeclaration sendMoney = type(project, "SendMoney");
        assertThat(Annotations.has(sendMoney, "Component")).isTrue();
        // @Deprecated относится к самой аннотации, а не к тому, что ею помечено
        assertThat(Annotations.has(sendMoney, "Deprecated")).isFalse();
        // Найденная напрямую аннотация - только та, что записана на узле: по ней берут и место находки
        assertThat(Annotations.find(sendMoney, "Component")).isEmpty();
        // Одноименная аннотация библиотеки: объявление проекта к ней не относится
        assertThat(Annotations.has(type(project, "Foreign"), "Component")).isFalse();

        ClassOrInterfaceDeclaration reports = type(project, "Reports");
        assertThat(Annotations.names(reports)).contains("TransactionalService", "Service", "Transactional");
        Optional<AnnotationExpr> transaction =
                TransactionalAnnotations.findEffective(reports.getMethodsByName("build").get(0));
        assertThat(transaction).isPresent();
        assertThat(TransactionalAnnotations.isReadOnly(transaction.get())).isTrue();
    }

    @Test
    void composedMappingKeepsMethodAndPath() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("AnonymousGetMapping.java", """
                @RequestMapping(method = RequestMethod.GET)
                public @interface AnonymousGetMapping {
                    String[] value() default {};
                }
                """);
        files.put("CodeController.java", """
                @RestController
                class CodeController {
                    @AnonymousGetMapping("/code")
                    String code() { return "1234"; }
                    @Timed
                    String plain() { return ""; }
                }
                """);
        ClassOrInterfaceDeclaration controller = type(RuleTests.project(files), "CodeController");

        Optional<AnnotationExpr> mapping = Mappings.find(controller.getMethodsByName("code").get(0));
        assertThat(mapping).isPresent();
        assertThat(Mappings.httpMethod(mapping.get())).isEqualTo("GET");
        assertThat(Mappings.paths(mapping.get())).containsExactly("/code");
        assertThat(Mappings.find(controller.getMethodsByName("plain").get(0))).isEmpty();
    }

    @Test
    void transactionComesFromInterfaceAndParent() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("OrderOperations.java", """
                interface OrderOperations {
                    @Transactional
                    void place(Order order);
                    void cancel(Order order);
                }
                """);
        files.put("BaseService.java", """
                @Transactional
                abstract class BaseService {
                }
                """);
        files.put("OrderService.java", """
                @Service
                class OrderService implements OrderOperations {
                    public void place(Order order) { }
                    public void cancel(Order order) { }
                }
                """);
        files.put("StockService.java", """
                @Service
                class StockService extends BaseService {
                    public void reserve() { }
                    void internal() { }
                }
                """);
        List<SourceFile> project = RuleTests.project(files);

        ClassOrInterfaceDeclaration orders = type(project, "OrderService");
        assertThat(TransactionalAnnotations.findEffective(orders.getMethodsByName("place").get(0))).isPresent();
        assertThat(TransactionalAnnotations.findEffective(orders.getMethodsByName("cancel").get(0))).isEmpty();
        ClassOrInterfaceDeclaration stock = type(project, "StockService");
        assertThat(TransactionalAnnotations.findEffective(stock.getMethodsByName("reserve").get(0))).isPresent();
        // Аннотация на классе действует на public-методы
        assertThat(TransactionalAnnotations.findEffective(stock.getMethodsByName("internal").get(0))).isEmpty();
    }

    @Test
    void modifyingQueryIsJudgedByItsCallers() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("RuleRepository.java", """
                import org.springframework.data.jpa.repository.Modifying;
                interface RuleRepository extends JpaRepository<Rule, Long> {
                    @Modifying
                    @Query("delete from Rule r where r.domain = :domain")
                    int deleteByDomain(String domain);
                    @Modifying
                    @Query("update Rule r set r.active = false where r.id = :id")
                    int deactivate(Long id);
                    @Modifying
                    @Query("update Rule r set r.hits = r.hits + 1 where r.id = :id")
                    int hit(Long id);
                    @Modifying
                    @Query("delete from Rule r where r.expired = true")
                    int purge();
                }
                """);
        files.put("RuleService.java", """
                @Service
                class RuleService {
                    private RuleRepository ruleRepository;
                    private TransactionTemplate transactionTemplate;
                    @Transactional
                    public void removeDomain(String domain) {
                        ruleRepository.deleteByDomain(domain);
                    }
                    public void deactivate(Long id) {
                        ruleRepository.deactivate(id);
                    }
                    public void hit(Long id) {
                        transactionTemplate.executeWithoutResult(status -> ruleRepository.hit(id));
                    }
                }
                """);
        files.put("PointRepository.java", """
                import org.springframework.data.jdbc.repository.query.Modifying;
                interface PointRepository extends CrudRepository<Point, Long> {
                    @Modifying
                    @Query("update point set x = 0")
                    int reset();
                }
                """);
        List<Violation> violations = RuleTests.checkProject(new ModifyingWithoutTransactionalRule(), files);

        // deleteByDomain и hit вызываются в транзакции; deactivate - вне ее; purge никто не вызывает;
        // у Spring Data JDBC транзакция запросу не нужна
        assertThat(violations).extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("6 " + Confidence.PROBABLE, "12 " + Confidence.SUSPICION);
        assertThat(violations.get(0).message()).contains("RuleService.deactivate");
    }

    @Test
    void writesNeedCommonTransactionOnlyWhenTheyRunTogether() {
        assertThat(RuleTests.lines(new MultipleWritesWithoutTransactionRule(), """
                @Service
                class OrderService {
                    private OrderRepository orderRepository;
                    private AuditRepository auditRepository;
                    private TransactionTemplate transactionTemplate;
                    public void together(Order order) {
                        orderRepository.save(order);
                        auditRepository.save(new Audit(order));
                    }
                    public void eitherBranch(Order order) {
                        if (order.isNew()) {
                            orderRepository.save(order);
                        } else {
                            auditRepository.save(new Audit(order));
                        }
                    }
                    public void earlyReturn(Order order) {
                        if (order.isDraft()) {
                            orderRepository.save(order);
                            return;
                        }
                        auditRepository.save(new Audit(order));
                    }
                    public Object returned(Order order) {
                        if (order.isDraft()) {
                            return orderRepository.save(order);
                        }
                        return auditRepository.save(new Audit(order));
                    }
                    public void inCallback(Order order) {
                        transactionTemplate.executeWithoutResult(status -> {
                            orderRepository.save(order);
                            auditRepository.save(new Audit(order));
                        });
                    }
                    public void afterBranch(Order order) {
                        if (order.isNew()) {
                            orderRepository.save(order);
                        }
                        auditRepository.save(new Audit(order));
                    }
                    public void bySwitch(Order order) {
                        switch (order.kind()) {
                            case NEW -> orderRepository.save(order);
                            case OLD -> auditRepository.save(new Audit(order));
                            default -> { }
                        }
                    }
                }
                """)).containsExactly(6, 36);
    }

    @Test
    void writesToStoreWithoutTransactionsAreNotCounted() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SessionRepository.java", """
                import org.springframework.data.repository.CrudRepository;
                interface SessionRepository extends CrudRepository<Session, String> {
                }
                """);
        files.put("Session.java", """
                import org.springframework.data.redis.core.RedisHash;
                @RedisHash("sessions")
                class Session {
                }
                """);
        files.put("EventRepository.java", """
                import org.springframework.data.mongodb.repository.MongoRepository;
                interface EventRepository extends MongoRepository<Event, String> {
                }
                """);
        files.put("UserRepository.java", """
                import org.springframework.data.jpa.repository.JpaRepository;
                interface UserRepository extends JpaRepository<User, Long> {
                }
                """);
        files.put("ProfileRepository.java", """
                import org.springframework.data.jpa.repository.JpaRepository;
                interface ProfileRepository extends JpaRepository<Profile, Long> {
                }
                """);
        files.put("LoginService.java", """
                @Service
                class LoginService {
                    private SessionRepository sessionRepository;
                    private EventRepository eventRepository;
                    private UserRepository userRepository;
                    private ProfileRepository profileRepository;
                    public void login(User user) {
                        userRepository.save(user);
                        sessionRepository.save(new Session());
                        eventRepository.save(new Event());
                    }
                    public void register(User user) {
                        userRepository.save(user);
                        profileRepository.save(new Profile());
                    }
                }
                """);
        // Redis транзакций не дает, MongoDB - только с объявленным менеджером транзакций
        assertThat(RuleTests.checkProject(new MultipleWritesWithoutTransactionRule(), files))
                .extracting(Violation::line).containsExactly(12);

        files.put("MongoConfig.java", """
                @Configuration
                class MongoConfig {
                    @Bean
                    MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
                        return new MongoTransactionManager(factory);
                    }
                }
                """);
        assertThat(RuleTests.checkProject(new MultipleWritesWithoutTransactionRule(), files))
                .extracting(Violation::line).containsExactly(7, 12);
    }

    @Test
    void genericThrowsIsNotReportedWhereNobodyCatchesOrItIsForced() {
        assertThat(RuleTests.lines(new GenericExceptionRule(), """
                class Handlers {
                    @GetMapping("/orders")
                    public String orders() throws Exception { return load(); }
                    @Around("@annotation(Timed)")
                    public Object timed(ProceedingJoinPoint joinPoint) throws Throwable { return joinPoint.proceed(); }
                    @KafkaListener(topics = "orders")
                    public void onOrder(String payload) throws Exception { load(); }
                    private String load() throws Exception { return Files.readString(path); }
                    private String forwarded() throws Exception { return load(); }
                    private void rethrow(Exception failure) throws Exception { throw failure; }
                    private void precise() throws Exception {
                        try { Files.delete(path); } catch (IOException e) { throw e; }
                    }
                    private void handled() throws Exception {
                        try { load(); } catch (Exception e) { log.warn("failed", e); }
                    }
                }
                """)).containsExactly(8, 11, 14);
    }

    @Test
    void bodyOfDeclarativeClientIsOutgoing() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("OrderClient.java", """
                interface OrderClient {
                    @PostExchange("/orders")
                    void send(@RequestBody OrderDto order);
                }
                """);
        files.put("OrderDto.java", """
                class OrderDto {
                    @NotNull
                    private String number;
                }
                """);
        files.put("OrderController.java", """
                @RestController
                class OrderController {
                    @PostMapping("/orders")
                    void create(@RequestBody OrderDto order) { }
                }
                """);
        assertThat(RuleTests.checkProject(new RequestBodyWithoutValidRule(), files))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("OrderController.java:4");
    }

    @Test
    void branchesThatNeverRunTogether() {
        ClassOrInterfaceDeclaration type = type(List.of(RuleTests.parse(java.nio.file.Path.of("Flow.java"), """
                class Flow {
                    void run(int kind) {
                        if (kind > 0) { first(); } else { second(); }
                        third();
                        switch (kind) {
                            case 1: fourth();
                            case 2: fifth(); break;
                            case 3: sixth();
                        }
                        if (kind == 7) { seventh(); throw new IllegalStateException(); }
                        eighth();
                    }
                }
                """)), "Flow");
        assertThat(Branches.areExclusive(call(type, "first"), call(type, "second"))).isTrue();
        assertThat(Branches.areExclusive(call(type, "first"), call(type, "third"))).isFalse();
        // Без break выполнение переходит в следующую ветку
        assertThat(Branches.areExclusive(call(type, "fourth"), call(type, "fifth"))).isFalse();
        assertThat(Branches.areExclusive(call(type, "fifth"), call(type, "sixth"))).isTrue();
        assertThat(Branches.areExclusive(call(type, "seventh"), call(type, "eighth"))).isTrue();
        assertThat(Branches.areExclusive(call(type, "third"), call(type, "eighth"))).isFalse();
    }

    private ClassOrInterfaceDeclaration type(List<SourceFile> project, String name) {
        return project.stream()
                .flatMap(source -> source.findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(type -> type.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private MethodCallExpr call(ClassOrInterfaceDeclaration type, String name) {
        return type.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
