package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.AnonymousEndpointBehindLoginRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:549: вход и вебхук, закрытые правилом "только для вошедших"
 */
class AnonymousEndpointRuleTest {
    private static final String HELPER = """
            @Component
            public class SecurityHelper {
                public void authenticate(User user, HttpServletRequest request) {
                    SecurityContext context = SecurityContextHolder.getContext();
                    context.setAuthentication(new UserAuthentication(user.getId()));
                    request.getSession(true).setAttribute("SPRING_SECURITY_CONTEXT", context);
                }
                public void logout() {
                    SecurityContextHolder.getContext().setAuthentication(null);
                }
            }
            """;
    private static final String LOGIN_CONTRACT = """
            @RequestMapping(value = "/security/one/factor")
            public interface LoginControllerMeta {
                @PostMapping(value = "/auth")
                Response auth(@RequestBody AuthRequest request, HttpServletRequest httpRequest);
                @PostMapping(value = "/logout")
                Response logout(HttpServletRequest request);
            }
            """;
    private static final String LOGIN_CONTROLLER = """
            @RestController
            @RequiredArgsConstructor
            public class LoginController implements LoginControllerMeta {
                private final UserService userService;
                private final SecurityHelper securityHelper;
                @Override
                public Response auth(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
                    User user = userService.check(request.getEmail(), request.getPassword());
                    securityHelper.authenticate(user, httpRequest);
                    return new Response();
                }
                @Override
                public Response logout(HttpServletRequest request) {
                    securityHelper.logout();
                    return new Response();
                }
            }
            """;
    private static final String WEBHOOK_CONTROLLER = """
            @RestController
            @RequestMapping("/telegram/bot")
            public class TelegramWebhookController {
                @PostMapping("/{botSecret}")
                public BotApiMethod<?> onUpdate(@PathVariable String botSecret, @RequestBody Update update) {
                    return bot.handle(botSecret, update);
                }
                @GetMapping("/status")
                public String status() {
                    return "ok";
                }
            }
            """;
    private static final String USER_CONTROLLER = """
            @RestController
            @RequestMapping("/admin/users")
            public class UserController {
                private final SecurityHelper securityHelper;
                // вход под другим пользователем - действие администратора, а не вход
                @PostMapping("/{id}/impersonate")
                public Response impersonate(@PathVariable long id, HttpServletRequest request) {
                    securityHelper.authenticate(users.get(id), request);
                    return new Response();
                }
                // пользователя заводит администратор: в контекст безопасности его не кладут
                @PostMapping("/register")
                public Response register(@RequestBody NewUser user) {
                    return users.create(user);
                }
                @DeleteMapping("/tokens/{token}")
                public void revoke(@PathVariable String token) {
                    tokens.revoke(token);
                }
            }
            """;

    private static String security(String rules) {
        return """
                @Configuration
                @EnableWebSecurity
                public class SecurityConfig {
                    @Bean
                    public SecurityFilterChain filterChain(HttpSecurity http, AuthFilter authFilter) throws Exception {
                        http.csrf(AbstractHttpConfigurer::disable)
                                .httpBasic(AbstractHttpConfigurer::disable)
                                .authorizeHttpRequests(authorize -> authorize
                %s
                                .addFilterBefore(authFilter, BasicAuthenticationFilter.class);
                        return http.build();
                    }
                }
                """.formatted(rules.indent(24).stripTrailing());
    }

    private static final String CLOSED = """
            .requestMatchers("/security/two/factor/**").permitAll()
            .requestMatchers("/actuator/**").permitAll()
            .anyRequest().authenticated())
            """;

    private static ProjectFixture project(Path dir, String security) {
        return new ProjectFixture(dir)
                .source("SecurityConfig.java", security)
                .source("SecurityHelper.java", HELPER)
                .source("LoginControllerMeta.java", LOGIN_CONTRACT)
                .source("LoginController.java", LOGIN_CONTROLLER)
                .source("TelegramWebhookController.java", WEBHOOK_CONTROLLER)
                .source("UserController.java", USER_CONTROLLER);
    }

    @Test
    void loginAndWebhookBehindAuthenticatedAreFound(@TempDir Path projects) {
        List<Violation> violations = project(projects.resolve("closed"), security(CLOSED))
                .check(new AnonymousEndpointBehindLoginRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("LoginController.java:6", "TelegramWebhookController.java:4");
        assertThat(violations.get(0).message()).contains("Вход 'LoginController.auth'",
                "POST /security/one/factor/auth", "'anyRequest().authenticated()'", "permitAll()");
        assertThat(violations.get(1).message()).contains("Вебхук 'TelegramWebhookController.onUpdate'",
                "POST /telegram/bot/{botSecret}", "requestMatchers(\"/telegram/bot/*\").permitAll()");
    }

    @Test
    void openEndpointsAreFine(@TempDir Path projects) {
        AnonymousEndpointBehindLoginRule rule = new AnonymousEndpointBehindLoginRule();
        assertThat(project(projects.resolve("open"), security("""
                .requestMatchers("/security/**", "/telegram/bot/*").permitAll()
                .anyRequest().authenticated())
                """)).check(rule)).isEmpty();
        // Правило для одного HTTP-метода: POST открыт
        assertThat(project(projects.resolve("method"), security("""
                .requestMatchers(HttpMethod.POST, "/security/one/factor/auth", "/telegram/bot/**").permitAll()
                .anyRequest().authenticated())
                """)).check(rule)).isEmpty();
        // Все открыто по умолчанию
        assertThat(project(projects.resolve("any"), security("""
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll())
                """)).check(rule)).isEmpty();
        // Адреса выведены из-под Spring Security целиком
        assertThat(project(projects.resolve("ignored"), security(CLOSED))
                .source("WebConfig.java", """
                        @Configuration
                        public class WebConfig {
                            @Bean
                            public WebSecurityCustomizer customizer() {
                                return web -> web.ignoring().requestMatchers("/security/one/**", "/telegram/**");
                            }
                        }
                        """)
                .check(rule)).isEmpty();
    }

    @Test
    void pictureThatIsNotFullyVisibleIsNotJudged(@TempDir Path projects) {
        AnonymousEndpointBehindLoginRule rule = new AnonymousEndpointBehindLoginRule();
        // Открытые адреса заданы не строками
        assertThat(project(projects.resolve("constants"), security("""
                .requestMatchers(openUrls()).permitAll()
                .anyRequest().authenticated())
                """)).check(rule)).isEmpty();
        // Войти можно и через Basic: закрытый обработчик может выдавать токен уже вошедшему
        assertThat(project(projects.resolve("basic"), security(CLOSED).replace(
                ".httpBasic(AbstractHttpConfigurer::disable)", ".httpBasic(Customizer.withDefaults())"))
                .check(rule)).isEmpty();
        // Цепочек несколько
        assertThat(project(projects.resolve("chains"), security(CLOSED))
                .source("ApiSecurityConfig.java", """
                        @Configuration
                        public class ApiSecurityConfig {
                            @Bean
                            public SecurityFilterChain api(HttpSecurity http) throws Exception {
                                return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                                        .build();
                            }
                        }
                        """)
                .check(rule)).isEmpty();
        // У адресов обработчиков есть приставка, которой нет в аннотациях
        assertThat(project(projects.resolve("prefix"), security(CLOSED))
                .write("src/main/resources/application.properties", "spring.mvc.servlet.path=/api\n")
                .check(rule)).isEmpty();
    }
}
