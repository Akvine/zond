package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.security.ResponseWriteWithoutEscapingRule;
import ru.akvine.zond.rules.security.SqlConcatenationRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Пути данных, которые отслеживание проходит помимо прямого присваивания: источники сервлета и JAX-RS, значение,
 * которое возвращает свой метод, коллекции и StringBuilder, а также ветки, которые не выполнятся никогда
 */
class TaintFlowExtensionsTest {
    private final SqlConcatenationRule rule = new SqlConcatenationRule();

    @Test
    void servletRequestSources() {
        assertThat(RuleTests.lines(rule, """
                class Search extends HttpServlet {
                    public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
                        String[] values = request.getParameterValues("id");
                        jdbc.query("select * from users where id = '" + values[0] + "'", mapper);
                        for (Cookie cookie : request.getCookies()) {
                            jdbc.query("select * from users where name = '" + cookie.getValue() + "'", mapper);
                        }
                        Enumeration<String> headers = request.getHeaders("X-Name");
                        String header = headers.nextElement();
                        jdbc.query("select * from users where name = '" + header + "'", mapper);
                        Map<String, String[]> map = request.getParameterMap();
                        jdbc.query("select * from users where code = '" + map.get("code")[0] + "'", mapper);
                        HttpHeaders reply = client.fetch().getHeaders();
                        jdbc.query("select * from users where tag = '" + reply.getFirst("tag") + "'", mapper);
                    }
                }
                """)).containsExactly(4, 6, 10, 12);
    }

    @Test
    void jaxRsParametersAreRequestData() {
        assertThat(RuleTests.lines(rule, """
                @Path("/users")
                class UsersResource {
                    @GET
                    public List<User> find(@QueryParam("name") String name, @Context UriInfo info) {
                        return jdbc.query("select * from users where name = '" + name + "'", mapper);
                    }
                    @POST
                    public void create(String body) {
                        jdbc.update("insert into users values ('" + body + "')");
                    }
                }
                """)).containsExactly(5, 9);
    }

    @Test
    void valueReturnedByOwnMethodIsTracedWithArgumentsOfThatCall() {
        assertThat(RuleTests.lines(rule, """
                class Report extends HttpServlet {
                    public void doPost(HttpServletRequest request, HttpServletResponse response) {
                        String param = request.getParameter("name");
                        String bar = decorate(param);
                        jdbc.query("select * from users where name = '" + bar + "'", mapper);
                        String fixed = decorate("guest");
                        jdbc.query("select * from users where name = '" + fixed + "'", mapper);
                        String safe = constant(param);
                        jdbc.query("select * from users where name = '" + safe + "'", mapper);
                    }
                    private String decorate(String value) {
                        return value.trim() + "_suffix";
                    }
                    private String constant(String value) {
                        return "bar";
                    }
                }
                """)).containsExactly(5);
    }

    @Test
    void valueReturnedByMethodOfNewObjectAndStaticHelper() {
        assertThat(RuleTests.lines(rule, """
                class Report extends HttpServlet {
                    public void doPost(HttpServletRequest request, HttpServletResponse response) {
                        String param = request.getParameter("name");
                        String bar = new Test().doSomething(request, param);
                        jdbc.query("select * from users where name = '" + bar + "'", mapper);
                        String other = Helpers.wrap(param);
                        jdbc.query("select * from users where name = '" + other + "'", mapper);
                    }
                    private class Test {
                        public String doSomething(HttpServletRequest request, String param) {
                            StringBuilder builder = new StringBuilder(param);
                            String bar = builder.append("_SafeStuff").toString();
                            return bar;
                        }
                    }
                }
                class Helpers {
                    static String wrap(String value) {
                        return "[" + value + "]";
                    }
                }
                """)).containsExactly(5, 7);
    }

    @Test
    void branchesThatNeverRunAreIgnored() {
        assertThat(RuleTests.lines(rule, """
                class Branches extends HttpServlet {
                    public void doPost(HttpServletRequest request, HttpServletResponse response) {
                        String param = request.getParameter("name");
                        int num = 106;
                        String ternary = (7 * 18) + num > 200 ? "This_should_always_happen" : param;
                        jdbc.query("select * from users where name = '" + ternary + "'", mapper);
                        String branch = "safe";
                        if ((500 / 42) + 196 > 200) {
                            branch = "constant";
                        } else {
                            branch = param;
                        }
                        jdbc.query("select * from users where name = '" + branch + "'", mapper);
                        String guess = "ABC";
                        char switchTarget = guess.charAt(1);
                        String chosen;
                        switch (switchTarget) {
                            case 'A':
                                chosen = param;
                                break;
                            case 'B':
                                chosen = "bob";
                                break;
                            case 'C':
                            case 'D':
                                chosen = param;
                                break;
                            default:
                                chosen = "bob's your uncle";
                                break;
                        }
                        jdbc.query("select * from users where name = '" + chosen + "'", mapper);
                        String reached = num > 100 ? param : "safe";
                        jdbc.query("select * from users where name = '" + reached + "'", mapper);
                    }
                }
                """)).containsExactly(34);
    }

    @Test
    void lookupByClientKeyReturnsServerObject() {
        assertThat(RuleTests.lines(rule, """
                class Login extends HttpServlet {
                    private final Map<String, String> tenants = new ConcurrentHashMap<>();
                    public void doPost(HttpServletRequest request, HttpServletResponse response) {
                        String key = request.getParameter("key");
                        String tenant = tenants.get(key);
                        jdbc.query("select * from users where tenant = '" + tenant + "'", mapper);
                        String fallback = tenants.getOrDefault("main", key);
                        jdbc.query("select * from users where tenant = '" + fallback + "'", mapper);
                        Path file = Paths.get("/data", key);
                        jdbc.query("select * from files where path = '" + file + "'", mapper);
                        String raw = request.getParameterMap().get("name")[0];
                        jdbc.query("select * from users where name = '" + raw + "'", mapper);
                    }
                }
                """)).containsExactly(8, 10, 12);
    }

    @Test
    void escapingProtectsOnlyItsOwnSink() {
        String code = """
                class Escaping extends HttpServlet {
                    public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
                        String param = request.getParameter("name");
                        String html = ESAPI.encoder().encodeForHTML(param);
                        jdbc.query("select * from users where name = '" + html + "'", mapper);
                        response.getWriter().println(html);
                        String sql = ESAPI.encoder().encodeForSQL(codec, param);
                        jdbc.query("select * from users where name = '" + sql + "'", mapper);
                        String back = new String(Base64.decodeBase64(Base64.encodeBase64(param.getBytes())));
                        jdbc.query("select * from users where name = '" + back + "'", mapper);
                        response.getWriter().println(back);
                    }
                }
                """;

        // Экранирование для HTML не защищает запрос к базе, Base64 туда и обратно не защищает ничего
        assertThat(RuleTests.lines(rule, code)).containsExactly(5, 10);
        assertThat(RuleTests.lines(new ResponseWriteWithoutEscapingRule(), code)).containsExactly(11);
    }

    @Test
    void valueOverwrittenBeforeUseIsIgnored() {
        assertThat(RuleTests.lines(rule, """
                class Overwrite extends HttpServlet {
                    public void doPost(HttpServletRequest request, HttpServletResponse response) {
                        String param = request.getParameter("name");
                        String bar = param;
                        bar = "safe";
                        jdbc.query("select * from users where name = '" + bar + "'", mapper);
                        String early = param;
                        jdbc.query("select * from users where name = '" + early + "'", mapper);
                        early = "late";
                        String grown = "prefix";
                        grown = grown + param;
                        jdbc.query("select * from users where name = '" + grown + "'", mapper);
                        String maybe = "safe";
                        if (param.isEmpty()) {
                            maybe = param;
                        }
                        jdbc.query("select * from users where name = '" + maybe + "'", mapper);
                    }
                }
                """)).containsExactly(8, 12, 17);
    }

    @Test
    void collectionsCarryWhatWasPutIntoThem() {
        assertThat(RuleTests.lines(rule, """
                class Collections extends HttpServlet {
                    public void doPost(HttpServletRequest request, HttpServletResponse response) {
                        String param = request.getParameter("name");
                        StringBuilder sql = new StringBuilder("select * from users where name = '");
                        sql.append(param).append("'");
                        jdbc.query("select count(*) from (" + sql.toString() + ")", mapper);
                        HashMap<String, Object> map = new HashMap<String, Object>();
                        map.put("keyA", "a-Value");
                        map.put("keyB", param);
                        jdbc.query("select * from users where name = '" + map.get("keyB") + "'", mapper);
                        jdbc.query("select * from users where name = '" + map.get("keyA") + "'", mapper);
                        List<String> values = new ArrayList<String>();
                        values.add("safe");
                        values.add(param);
                        values.add("moresafe");
                        values.remove(0);
                        String first = values.get(0);
                        String last = values.get(1);
                        jdbc.query("select * from users where name = '" + first + "'", mapper);
                        jdbc.query("select * from users where name = '" + last + "'", mapper);
                    }
                }
                """)).containsExactly(6, 10, 19);
    }
}
