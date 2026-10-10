package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.GetMappingModifiesStateRule;
import ru.akvine.zond.rules.security.ObjectAccessWithoutOwnerCheckRule;
import ru.akvine.zond.rules.security.SecretInRequestParameterRule;
import ru.akvine.zond.rules.security.SecurityMatcherOrderRule;
import ru.akvine.zond.rules.security.VulnerableDependencyRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:378 - jr:382: порядок правил доступа, изменение данных по GET, секреты в адресе,
 * уязвимые версии библиотек и доступ к чужим записям
 */
class AccessRulesTest {

    @TempDir
    Path dir;

    @Test
    void generalAccessRuleMustNotStandAboveSpecific() {
        SecurityMatcherOrderRule rule = new SecurityMatcherOrderRule();
        List<Violation> violations = RuleTests.check(rule, """
                @Configuration
                class SecurityConfig {
                    @Bean
                    SecurityFilterChain api(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(auth -> auth
                                .requestMatchers("/api/**").permitAll()
                                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                                .requestMatchers("/api/users/me").permitAll()
                                .requestMatchers(HttpMethod.POST, "/files/**").hasRole("UPLOADER")
                                .requestMatchers("/files/**").authenticated()
                                .requestMatchers("/reports/*/pdf").hasRole("REPORTS")
                                .anyRequest().authenticated());
                        return http.build();
                    }
                    @Bean
                    SecurityFilterChain old(HttpSecurity http) throws Exception {
                        http.authorizeRequests()
                                .antMatchers("/public/**").permitAll()
                                .anyRequest().authenticated()
                                .antMatchers("/admin/**").hasRole("ADMIN");
                        return http.build();
                    }
                    @Bean
                    SecurityFilterChain correct(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(auth -> auth
                                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                                .requestMatchers("/api/**").permitAll()
                                .anyRequest().authenticated());
                        return http.build();
                    }
                }
                """);

        // 8 - то же правило доступа, что и выше: лишнее, но безвредное; 10 - правило для POST не охватывает
        // остальные методы; anyRequest в конце перекрытым не бывает
        assertThat(violations).extracting(Violation::line).containsExactly(7, 20);
        assertThat(violations.get(0).message())
                .contains("requestMatchers(\"/api/admin/**\").hasRole(\"ADMIN\")")
                .contains("requestMatchers(\"/api/**\").permitAll()");
        assertThat(rule.confidence()).isEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void getHandlerMustNotChangeData() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("ItemController.java", """
                        @RestController
                        class ItemController {
                            private ItemService itemService;
                            private ItemRepository itemRepository;
                            private AuditLogRepository auditLogRepository;
                            @GetMapping("/items/{id}/delete")
                            void delete(@PathVariable Long id) {
                                itemRepository.deleteById(id);
                            }
                            @GetMapping("/items/{id}")
                            Item get(@PathVariable Long id) {
                                auditLogRepository.save(new AuditLog(id));
                                return itemRepository.findById(id).orElseThrow();
                            }
                            @GetMapping("/items/{id}/archive")
                            void archive(@PathVariable Long id) {
                                itemService.archive(id);
                            }
                            @PostMapping("/items")
                            void create(@RequestBody Item item) {
                                itemRepository.save(item);
                            }
                            @GetMapping("/confirm")
                            void confirm(@RequestParam String code) {
                                itemRepository.save(new Item(code));
                            }
                            @RequestMapping(value = "/items/{id}/touch", method = RequestMethod.GET)
                            void touch(@PathVariable Long id) {
                                itemRepository.save(new Item(id));
                            }
                        }
                        """)
                .source("ItemService.java", """
                        class ItemService {
                            private ItemRepository itemRepository;
                            void archive(Long id) {
                                Item item = itemRepository.findById(id).orElseThrow();
                                item.setArchived(true);
                                itemRepository.save(item);
                            }
                        }
                        """);

        // Запись в журнал - учет, а не изменение данных; ссылку подтверждения из письма кроме GET открыть нечем
        assertThat(project.lines(new GetMappingModifiesStateRule())).containsExactly(
                "ItemController.java:17", "ItemController.java:29", "ItemController.java:8");

        // Адрес объявлен в интерфейсе, а код лежит в контроллере, который его реализует
        ProjectFixture byContract = new ProjectFixture(dir)
                .source("TagApi.java", """
                        interface TagApi {
                            @GetMapping("/tags/{id}/drop")
                            void drop(@PathVariable Long id);
                            @PostMapping("/tags")
                            void create(@RequestBody Tag tag);
                        }
                        """)
                .source("TagController.java", """
                        @RestController
                        class TagController implements TagApi {
                            private TagRepository tagRepository;
                            public void drop(Long id) {
                                tagRepository.deleteById(id);
                            }
                            public void create(Tag tag) {
                                tagRepository.save(tag);
                            }
                        }
                        """);
        assertThat(byContract.lines(new GetMappingModifiesStateRule())).containsExactly("TagController.java:5");
    }

    @Test
    void secretMustNotTravelInUrl() {
        List<Integer> lines = RuleTests.lines(new SecretInRequestParameterRule(), """
                @RestController
                class AuthController {
                    @GetMapping("/login")
                    String login(@RequestParam String user, @RequestParam String password) { return ""; }
                    @PostMapping("/login")
                    String form(@RequestParam String user, @RequestParam String password) { return ""; }
                    @GetMapping("/reset/{token}")
                    String reset(@PathVariable String token) { return ""; }
                    @GetMapping("/data")
                    String data(@RequestParam("api_key") String key, @RequestHeader("X-Token") String token) { return ""; }
                    @GetMapping("/items")
                    String items(@RequestParam String tokenType, @RequestParam int page) { return ""; }
                    @DeleteMapping("/sessions")
                    void logout(@RequestParam String accessToken) {}
                }
                @FeignClient("partner")
                interface PartnerClient {
                    @GetMapping("/partner")
                    String call(@RequestParam("token") String token);
                }
                interface WebhookApi {
                    @PostMapping("/hook/{botSecret}")
                    void onUpdate(@PathVariable("botSecret") String botSecret, @RequestBody String update);
                }
                """);

        // У POST параметр может прийти из тела формы; заголовок в адрес не попадает; tokenType - не сам секрет;
        // адрес чужого сервиса задает он сам, а интерфейс с адресами своего контроллера проверяется как контроллер
        assertThat(lines).containsExactly(4, 8, 10, 14, 23);
    }

    @Test
    void knownVulnerableVersionsAreReported() {
        ProjectFixture maven = new ProjectFixture(dir.resolve("maven")).write("pom.xml", """
                <project>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.6.3</version>
                    </parent>
                    <properties>
                        <log4j.version>2.14.1</log4j.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.logging.log4j</groupId>
                            <artifactId>log4j-core</artifactId>
                            <version>${log4j.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-text</artifactId>
                            <version>1.10.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.yaml</groupId>
                            <artifactId>snakeyaml</artifactId>
                            <version>1.33</version>
                        </dependency>
                        <dependency>
                            <groupId>com.h2database</groupId>
                            <artifactId>h2</artifactId>
                            <version>1.4.200</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <version>42.3.1</version>
                        </dependency>
                        <dependency>
                            <groupId>log4j</groupId>
                            <artifactId>log4j</artifactId>
                            <version>1.2.17</version>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        VulnerableDependencyRule rule = new VulnerableDependencyRule();
        List<String> messages = maven.check(rule).stream().map(Violation::message).toList();

        // Исправленная версия, библиотека только для тестов и зависимость без версии в отчет не попадают
        assertThat(messages).hasSize(5);
        assertThat(messages).anyMatch(message -> message.contains("log4j-core:2.14.1") && message.contains("Log4Shell"));
        assertThat(messages).anyMatch(message -> message.contains("snakeyaml:1.33") && message.contains("2.0"));
        assertThat(messages).anyMatch(message -> message.contains("postgresql:42.3.1") && message.contains("42.3.2"));
        assertThat(messages).anyMatch(message -> message.contains("log4j:log4j:1.2.17") && message.contains("замените"));
        assertThat(messages).anyMatch(message -> message.contains("Spring Boot 2.6.3") && message.contains("Spring4Shell"));
        assertThat(rule.confidence()).isEqualTo(Confidence.CONFIRMED);

        ProjectFixture gradle = new ProjectFixture(dir.resolve("gradle")).write("build.gradle", """
                plugins {
                    id 'org.springframework.boot' version '2.5.4'
                }
                dependencies {
                    implementation 'org.apache.commons:commons-text:1.9'
                    implementation 'org.yaml:snakeyaml:2.2'
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                    testImplementation 'com.h2database:h2:1.4.200'
                }
                """);
        assertThat(gradle.lines(rule)).containsExactly("build.gradle:2", "build.gradle:5");

        ProjectFixture fresh = new ProjectFixture(dir.resolve("fresh")).write("build.gradle", """
                plugins {
                    id 'org.springframework.boot' version '3.3.1'
                }
                dependencies {
                    implementation 'org.apache.logging.log4j:log4j-core:2.23.1'
                    implementation 'org.postgresql:postgresql:42.7.3'
                }
                """);
        assertThat(fresh.lines(rule)).isEmpty();
    }

    @Test
    void recordFetchedByIdNeedsOwnerCheck() {
        ObjectAccessWithoutOwnerCheckRule rule = new ObjectAccessWithoutOwnerCheckRule();
        String controller = """
                @RestController
                class DocumentController {
                    private DocumentRepository documentRepository;
                    private CountryRepository countryRepository;
                    private DocumentService documentService;
                    @GetMapping("/documents/{id}")
                    Document get(@PathVariable Long id) {
                        return documentRepository.findById(id).orElseThrow();
                    }
                    @DeleteMapping("/documents/{id}")
                    void delete(@PathVariable Long id) {
                        documentService.delete(id);
                    }
                    @GetMapping("/countries/{id}")
                    Country country(@PathVariable Long id) {
                        return countryRepository.findById(id).orElseThrow();
                    }
                    @GetMapping("/my/documents/{id}")
                    Document mine(@PathVariable Long id, Principal principal) {
                        return documentRepository.findById(id).orElseThrow();
                    }
                    @PreAuthorize("hasRole('ADMIN')")
                    @GetMapping("/admin/documents/{id}")
                    Document admin(@PathVariable Long id) {
                        return documentRepository.findById(id).orElseThrow();
                    }
                    @PutMapping("/documents/{id}")
                    void rename(@PathVariable Long id, @RequestBody String title) {
                        documentService.rename(id, title);
                    }
                }
                """;
        ProjectFixture project = new ProjectFixture(dir)
                .source("DocumentController.java", controller)
                .source("Document.java", """
                        @Entity
                        class Document {
                            @Id
                            private Long id;
                            @ManyToOne
                            private User owner;
                            private String title;
                        }
                        """)
                .source("Country.java", """
                        @Entity
                        class Country {
                            @Id
                            private Long id;
                            private String name;
                        }
                        """)
                .source("DocumentRepository.java", "interface DocumentRepository extends JpaRepository<Document, Long> {}")
                .source("CountryRepository.java", "interface CountryRepository extends JpaRepository<Country, Long> {}")
                .source("DocumentService.java", """
                        class DocumentService {
                            private DocumentRepository documentRepository;
                            void delete(Long documentId) {
                                documentRepository.deleteById(documentId);
                            }
                            void rename(Long id, String title) {
                                Document document = documentRepository.findById(id).orElseThrow();
                                if (!document.getOwner().equals(currentUser())) {
                                    throw new AccessDeniedException("not yours");
                                }
                                document.setTitle(title);
                            }
                        }
                        """);

        // Справочник без владельца, обработчик со знанием о пользователе, проверка прав аннотацией и сервис,
        // который сам сверяет владельца, вопросов не вызывают
        assertThat(project.lines(rule)).containsExactly("DocumentController.java:11", "DocumentController.java:7");
        assertThat(rule.confidence()).isEqualTo(Confidence.SUSPICION);

        // В проекте без аутентификации владельцев нет вовсе
        ProjectFixture open = new ProjectFixture(dir)
                .source("NoteController.java", """
                        @RestController
                        class NoteController {
                            private NoteRepository noteRepository;
                            @GetMapping("/notes/{id}")
                            Note get(@PathVariable Long id) {
                                return noteRepository.findById(id).orElseThrow();
                            }
                        }
                        """)
                .source("Note.java", "@Entity class Note { @Id private Long id; private Long userId; }")
                .source("NoteRepository.java", "interface NoteRepository extends JpaRepository<Note, Long> {}");
        assertThat(open.lines(rule)).isEmpty();
    }
}
