package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.AdminPathOpenToAllRule;
import ru.akvine.zond.rules.security.ControllerOutsideProtectedPrefixRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:560, jr:561: доступ, который настройка Spring Security открывает не тем адресам, - контроллер, выпавший
 * из закрытой приставки, и административные адреса под permitAll
 */
class AccessOutliersRulesTest {
    private static final String SECURITY = """
            package shop.config;
            @Configuration
            public class SecurityConfig extends WebSecurityConfigurerAdapter {
                @Override
                protected void configure(HttpSecurity http) throws Exception {
                    http.authorizeRequests()
                        .antMatchers("/actuator/health").permitAll()
                        .antMatchers("/shop/admin/**").hasRole("ADMIN")
                        .antMatchers("/shop/**").permitAll()
                        .anyRequest().authenticated();
                }
            }
            """;
    private static final String BASE = """
            package shop.rest;
            public abstract class BaseController<D> {
                @PostMapping("/list")
                public List<D> list(@RequestBody Filter filter) {
                    return service().list(filter);
                }
                @PostMapping("/delete")
                public void delete(@RequestBody IdRequest request) {
                    service().delete(request.getId());
                }
                protected abstract CrudService<D> service();
            }
            """;

    private static String crud(String name, String path) {
        return """
                package shop.rest.admin;
                import shop.rest.BaseController;
                @RestController
                @RequestMapping(value = "%s")
                public class %sController extends BaseController<%sDto> {
                    private final %sService service;
                    @Override
                    protected CrudService<%sDto> service() {
                        return service;
                    }
                }
                """.formatted(path, name, name, name, name);
    }

    private static ProjectFixture shop(Path project, String cashierPath) {
        return new ProjectFixture(project)
                .source("SecurityConfig.java", SECURITY)
                .source("BaseController.java", BASE)
                .source("DeskController.java", crud("Desk", "/shop/admin/desk"))
                .source("PartnerController.java", crud("Partner", "/shop/admin/partner"))
                .source("TerminalController.java", crud("Terminal", "/shop/admin/terminal"))
                .source("CashierController.java", crud("Cashier", cashierPath))
                .source("AdminAuthController.java", """
                        package shop.rest.admin;
                        @RestController
                        @RequestMapping("/shop/auth")
                        public class AdminAuthController {
                            @PostMapping("/login")
                            public void login(@RequestBody LoginRequest request) {
                            }
                        }
                        """)
                .source("CatalogController.java", """
                        package shop.rest.catalog;
                        @RestController
                        @RequestMapping("/shop/catalog")
                        public class CatalogController {
                            @GetMapping("/items")
                            public List<Item> items() {
                                return List.of();
                            }
                        }
                        """);
    }

    @Test
    void controllerThatFellOutOfProtectedPrefixIsFound(@TempDir Path project) {
        List<Violation> violations = shop(project, "/shop/cashier").check(new ControllerOutsideProtectedPrefixRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("CashierController.java:5");
        assertThat(violations.get(0).message()).contains(
                "'CashierController'", "пакете 'shop.rest.admin'", "3 контроллерами",
                "'antMatchers(\"/shop/admin/**\").hasRole(\"ADMIN\")'", "'/shop/cashier/list'",
                "'antMatchers(\"/shop/**\").permitAll()'", "без входа");
    }

    @Test
    void controllerUnderProtectedPrefixOrWithOwnCheckIsFine(@TempDir Path projects) {
        ControllerOutsideProtectedPrefixRule rule = new ControllerOutsideProtectedPrefixRule();
        assertThat(shop(projects.resolve("inside"), "/shop/admin/cashier").check(rule)).isEmpty();
        // Доступ проверяет аннотация на самом классе
        assertThat(shop(projects.resolve("annotated"), "/shop/admin/cashier")
                .source("ReportController.java", """
                        package shop.rest.admin;
                        @RestController
                        @RequestMapping("/shop/report")
                        @PreAuthorize("hasRole('ADMIN')")
                        public class ReportController {
                            @GetMapping("/daily")
                            public Report daily() {
                                return new Report();
                            }
                        }
                        """)
                .check(rule)).isEmpty();
    }

    @Test
    void packageWithFewProtectedControllersIsNotJudged(@TempDir Path project) {
        // Закрытый сосед один: пакет еще не говорит о том, что и этот контроллер должен быть закрыт
        assertThat(new ProjectFixture(project)
                .source("SecurityConfig.java", SECURITY)
                .source("BaseController.java", BASE)
                .source("DeskController.java", crud("Desk", "/shop/admin/desk"))
                .source("CashierController.java", crud("Cashier", "/shop/cashier"))
                .check(new ControllerOutsideProtectedPrefixRule())).isEmpty();
    }

    private static final String OPEN_ADMIN = """
            package shop.config;
            @Configuration
            public class SecurityConfig {
                @Bean
                public SecurityFilterChain chain(HttpSecurity http) throws Exception {
                    http.authorizeHttpRequests(requests -> requests
                            .requestMatchers("/admin/login", "/admin/assets/**").permitAll()
                            .requestMatchers("/admin/**").permitAll()
                            .anyRequest().hasRole("USER"));
                    return http.build();
                }
            }
            """;
    private static final String ADMIN_CONTROLLER = """
            package shop.rest;
            @RestController
            public class AdminController implements AdminApi {
                @Override
                public Response upload(String secret, MultipartFile file) {
                    return new Response();
                }
            }
            """;
    private static final String ADMIN_API = """
            package shop.rest;
            @RequestMapping("/admin")
            public interface AdminApi {
                @PostMapping("/team/csv")
                Response upload(@RequestParam("secret") String secret, @RequestParam("file") MultipartFile file);
            }
            """;

    @Test
    void adminSubtreeOpenToEveryoneIsFound(@TempDir Path project) {
        List<Violation> violations = new ProjectFixture(project)
                .source("SecurityConfig.java", OPEN_ADMIN)
                .source("AdminApi.java", ADMIN_API)
                .source("AdminController.java", ADMIN_CONTROLLER)
                .check(new AdminPathOpenToAllRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("SecurityConfig.java:8");
        assertThat(violations.get(0).message())
                .contains("'requestMatchers(\"/admin/**\").permitAll()'", "AdminController", "hasRole");
    }

    @Test
    void adminSubtreeThatIsClosedOrHasNoHandlersIsFine(@TempDir Path projects) {
        AdminPathOpenToAllRule rule = new AdminPathOpenToAllRule();
        // Обработчиков под открытым поддеревом нет: это статика
        assertThat(new ProjectFixture(projects.resolve("static"))
                .source("SecurityConfig.java", OPEN_ADMIN).check(rule)).isEmpty();
        // Поддерево закрыто ролью, открыта только страница входа
        assertThat(new ProjectFixture(projects.resolve("closed"))
                .source("SecurityConfig.java", OPEN_ADMIN.replace(
                        "(\"/admin/**\").permitAll()", "(\"/admin/**\").hasRole(\"ADMIN\")"))
                .source("AdminApi.java", ADMIN_API)
                .source("AdminController.java", ADMIN_CONTROLLER)
                .check(rule)).isEmpty();
        // Доступ проверяют аннотации на обработчиках
        assertThat(new ProjectFixture(projects.resolve("annotated"))
                .source("SecurityConfig.java", OPEN_ADMIN)
                .source("AdminController.java", """
                        package shop.rest;
                        @RestController
                        @RequestMapping("/admin")
                        public class AdminController {
                            @PreAuthorize("hasRole('ADMIN')")
                            @PostMapping("/team")
                            public Response team() {
                                return new Response();
                            }
                        }
                        """)
                .check(rule)).isEmpty();
    }
}
