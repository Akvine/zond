package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.ValueWithoutDefaultRule;
import ru.akvine.zond.rules.logical.MissingConfigPropertyRule;
import ru.akvine.zond.rules.security.UnboundedRequestCollectionRule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила пользуются тем, что уже известно о проекте: какие свойства заданы в настройках и какие классы
 * на самом деле принимают данные запроса
 */
class ProjectKnowledgeRulesTest {
    private static final String SETTINGS = """
            class Settings {
                @Value("${app.name}")
                private String name;
                @Value("${app.timeout:30}")
                private int timeout;
                @Value("${app.debug-url}")
                private String debugUrl;
                @Value("${app.missing}")
                private String missing;
                @Value("${DB_HOST}")
                private String host;
                @Value(value = "${app.limits}")
                private List<Integer> limits;
                @Value("${app.only-in-tests}")
                private String testOnly;
                @Value(value = "${app.absent}")
                private String absent;
            }
            """;

    private final ConfigFile main = config("src/main/resources/application.properties",
            "app.name", "app.limits[0]", "app.limits[1]");
    private final ConfigFile local = config("src/main/resources/application-local.properties", "app.debug-url");
    private final ConfigFile dev = config("src/main/resources/application-dev.yml", "app.debugUrl");
    private final ConfigFile tests = config("src/test/resources/application.properties", "app.only-in-tests");

    @Test
    void valueWithoutDefaultIsFineWhenPropertyIsInMainFile() {
        List<Violation> violations = new ValueWithoutDefaultRule()
                .checkContext(context("Settings.java", SETTINGS, main, local, dev, tests));

        // app.name и app.limits заданы в основном файле; app.missing нет нигде - о нем скажет jr:301
        assertThat(violations).extracting(Violation::line).containsExactly(6, 10, 14);
        assertThat(violations.get(0).message())
                .contains("задано только в application-dev.yml, application-local.properties");
        assertThat(violations.get(1).message()).contains("'DB_HOST' - переменная окружения");
        assertThat(violations.get(2).message()).contains("задано только в application.properties из тестов");
    }

    @Test
    void testCodeSeesTestSettings() {
        List<Violation> violations = new ValueWithoutDefaultRule().checkContext(context("SettingsTest.java", """
                @SpringBootTest
                class SettingsTest {
                    @Value("${app.only-in-tests}")
                    private String testOnly;
                    @Value("${app.name}")
                    private String name;
                }
                """, main, tests));

        assertThat(violations).isEmpty();
    }

    @Test
    void valueWithoutDefaultIsReportedWhenSettingsAreNotScanned() {
        // Файлы настроек в проверку не попали - задано свойство или нет, неизвестно
        List<Violation> violations = new ValueWithoutDefaultRule().checkContext(context("Settings.java", SETTINGS));

        assertThat(violations).extracting(Violation::line).containsExactly(2, 6, 8, 10, 12, 14, 16);
        assertThat(violations.get(0).message()).contains("@Value(\"${app.name}\")").contains("если свойства нет в окружении");
    }

    @Test
    void missingPropertyIsFoundInBothAnnotationForms() {
        List<Violation> violations = new MissingConfigPropertyRule()
                .checkContext(context("Settings.java", SETTINGS, main, local, dev, tests));

        // Запись @Value(value = "...") раньше не разбиралась
        assertThat(violations).extracting(Violation::line).containsExactly(8, 16);
    }

    @Test
    void collectionsAreCheckedInClassesThatReallyReceiveRequests() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("OrderController.java", """
                @RestController
                class OrderController implements OrderApi {
                    @PostMapping("/orders")
                    void create(@Valid @RequestBody OrderRequest request) {}
                    @PostMapping("/search")
                    List<OrderView> search(@ModelAttribute SearchForm form) { return List.of(); }
                    @Override
                    public void importAll(List<ImportRow> rows) {}
                }
                """);
        files.put("OrderApi.java", """
                interface OrderApi {
                    @PostMapping("/import")
                    void importAll(@RequestBody List<ImportRow> rows);
                }
                """);
        files.put("OrderRequest.java", """
                class OrderRequest extends BaseRequest {
                    private List<OrderLine> lines;
                    @Size(max = 10)
                    private List<String> tags;
                    private byte[] attachment;
                    private String[] labels;
                    private Address address;
                }
                """);
        files.put("BaseRequest.java", "class BaseRequest { private Map<String, String> attributes; }");
        files.put("OrderLine.java", "class OrderLine { private List<Long> optionIds; }");
        files.put("Address.java", "class Address { private String city; }");
        files.put("SearchForm.java", "class SearchForm { private Set<String> statuses; }");
        files.put("ImportRow.java", "class ImportRow { private List<String> cells; }");
        files.put("OrderView.java", "class OrderView { private List<String> lines; }");
        files.put("PartnerClient.java", """
                @FeignClient("partner")
                interface PartnerClient {
                    @PostMapping("/push")
                    void push(@RequestBody PushRequest request);
                }
                """);
        files.put("PushRequest.java", """
                class PushRequest {
                    @NotNull
                    private List<String> items;
                }
                """);
        files.put("OrderListener.java", """
                @Component
                class OrderListener {
                    @KafkaListener(topics = "orders")
                    void onEvent(OrderEvent event) {}
                }
                """);
        files.put("OrderEvent.java", "class OrderEvent { private List<Long> orderIds; }");

        List<Violation> violations = RuleTests.checkProject(new UnboundedRequestCollectionRule(), files);

        // PushRequest приложение отправляет само, OrderView отдает в ответе: имя и @NotNull о входящих данных
        // не говорят. byte[] приходит одной строкой
        assertThat(violations).extracting(violation -> violation.file().toString() + ":" + violation.line())
                .containsExactlyInAnyOrder(
                        "OrderRequest.java:2", "OrderRequest.java:6", "BaseRequest.java:1", "OrderLine.java:1",
                        "SearchForm.java:1", "ImportRow.java:1", "OrderEvent.java:1");
        assertThat(violations).extracting(Violation::confidence).containsOnly(Confidence.PROBABLE);
    }

    @Test
    void classesWithSameNameAreToldApartByImports() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("api/ClientController.java", """
                package shop.api;
                import org.telegram.telegrambots.meta.api.objects.Update;
                import shop.api.dto.SendMessageRequest;
                @RestController
                class ClientController {
                    @PostMapping("/send")
                    void send(@RequestBody SendMessageRequest request) {}
                    @PostMapping("/webhook")
                    void webhook(@RequestBody Update update) {}
                    @PostMapping("/filter")
                    void filter(@RequestBody ClientFilter filter) {}
                }
                """);
        files.put("api/ClientFilter.java", "package shop.api;\nclass ClientFilter { private List<Long> ids; }");
        files.put("api/dto/SendMessageRequest.java",
                "package shop.api.dto;\npublic class SendMessageRequest { private List<String> chatIds; }");
        files.put("partner/SendMessageRequest.java",
                "package shop.partner;\npublic class SendMessageRequest { private List<String> attachments; }");
        files.put("partner/Update.java", "package shop.partner;\npublic class Update { private List<String> events; }");

        List<Violation> violations = RuleTests.checkProject(new UnboundedRequestCollectionRule(), files);

        // Запрос партнеру носит то же имя, что и входной; Update в контроллере - класс библиотеки, а не свой
        assertThat(violations).extracting(violation -> violation.file().toString().replace('\\', '/') + ":" + violation.line())
                .containsExactlyInAnyOrder("api/dto/SendMessageRequest.java:2", "api/ClientFilter.java:2");
    }

    @Test
    void withoutHandlersRequestClassesAreGuessedByName() {
        // Контроллеров в проверке нет: остается судить по имени, и уверенность ниже
        List<Violation> violations = RuleTests.check(new UnboundedRequestCollectionRule(), """
                class GenerateRequest {
                    private List<Column> columns;
                    private byte[] template;
                }
                class OrderView {
                    private List<String> lines;
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(2);
        assertThat(violations).extracting(Violation::confidence).containsOnly(Confidence.SUSPICION);
    }

    private ScanContext context(String name, String code, ConfigFile... configs) {
        return new ScanContext(Path.of("."), List.of(RuleTests.parse(Path.of(name), code)), List.of(configs), List.of());
    }

    private ConfigFile config(String path, String... keys) {
        List<ConfigProperty> properties = new ArrayList<>();
        for (int index = 0; index < keys.length; index++) {
            properties.add(new ConfigProperty(keys[index], "value", index + 1));
        }
        return new ConfigFile(Path.of(path), properties);
    }
}
