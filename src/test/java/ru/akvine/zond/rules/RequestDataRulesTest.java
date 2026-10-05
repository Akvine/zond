package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.security.ExpressionInjectionRule;
import ru.akvine.zond.rules.security.LogInjectionRule;
import ru.akvine.zond.rules.security.ReflectionFromRequestRule;
import ru.akvine.zond.rules.security.RegexFromRequestRule;
import ru.akvine.zond.rules.security.ResponseWriteWithoutEscapingRule;
import ru.akvine.zond.rules.security.SsrfRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:207 - jr:212: данные запроса, дошедшие до опасного места
 */
class RequestDataRulesTest {

    @Test
    void ssrf() {
        assertThat(RuleTests.lines(new SsrfRule(), """
                class Proxy {
                    @GetMapping("/fetch")
                    String fetch(@RequestParam String url, @RequestParam String id) {
                        restTemplate.getForObject(url, String.class);
                        restTemplate.getForObject("https://api.example.org/items/" + id, String.class);
                        new URL(url);
                        restTemplate.getForObject(SERVICE_URL, String.class);
                        return "";
                    }
                }
                """)).containsExactly(4, 6);
    }

    @Test
    void logInjection() {
        assertThat(RuleTests.lines(new LogInjectionRule(), """
                class Users {
                    @GetMapping("/users")
                    String find(@RequestParam String name, @RequestParam Long id) {
                        log.info("search {}", name);
                        log.info("search {}", name.replaceAll("[^a-z]", "_"));
                        log.info("search {}", id);
                        return "";
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void regexFromRequest() {
        assertThat(RuleTests.lines(new RegexFromRequestRule(), """
                class Search {
                    @GetMapping("/search")
                    boolean search(@RequestParam String pattern, @RequestParam String text) {
                        Pattern.compile(pattern);
                        text.matches(pattern);
                        text.replaceAll(pattern, "");
                        Pattern.compile(Pattern.quote(pattern));
                        text.matches("[a-z]+");
                        return encoder.matches(pattern, text);
                    }
                }
                """)).containsExactly(4, 5, 6);
    }

    @Test
    void reflectionFromRequest() {
        assertThat(RuleTests.lines(new ReflectionFromRequestRule(), """
                class Plugins {
                    @PostMapping("/run")
                    void run(@RequestParam String className) throws Exception {
                        Class.forName(className);
                        Class.forName("demo.Plugin");
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void expressionInjection() {
        assertThat(RuleTests.lines(new ExpressionInjectionRule(), """
                class Rules {
                    @PostMapping("/eval")
                    Object eval(@RequestParam String expression, @RequestParam String user) {
                        parser.parseExpression(expression);
                        ldapTemplate.search("ou=users", "(uid=" + user + ")", mapper);
                        return parser.parseExpression("1 + 1");
                    }
                }
                """)).containsExactly(4, 5);
    }

    @Test
    void responseWriteWithoutEscaping() {
        assertThat(RuleTests.lines(new ResponseWriteWithoutEscapingRule(), """
                class Hello {
                    @GetMapping("/hello")
                    void hello(@RequestParam String name, HttpServletResponse response) throws Exception {
                        response.getWriter().write("<h1>" + name + "</h1>");
                        response.getWriter().write(HtmlUtils.htmlEscape(name));
                        PrintWriter writer = response.getWriter();
                        writer.println(name);
                    }
                }
                """)).containsExactly(4, 7);
    }
}
