package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.ManualBeanLookupRule;
import ru.akvine.zond.rules.codesmell.StaticContainerHolderRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:164 и jr:423: ApplicationContext и BeanFactory в коде приложения
 */
class SpringContainerRulesTest {

    @Test
    void manualLookupThroughContainerIsReported() {
        String code = """
                @Service
                class Router {
                    private final ApplicationContext spring;
                    private final AutowireCapableBeanFactory factory;
                    void route(Order order) {
                        Handler handler = spring.getBean(order.type() + "Handler", Handler.class);
                        Map<String, Handler> all = spring.getBeansOfType(Handler.class);
                        Report report = new Report();
                        factory.autowireBean(report);
                        PriceService prices = SpringContextHolder.getBean(PriceService.class);
                        Object other = registry.getBean("x");
                        event.getApplicationContext().getBean(Audit.class);
                    }
                }
                @Configuration
                class Config {
                    void init() {
                        context.getBean(OrderService.class);
                    }
                }
                class HandlerRegistrar implements BeanFactoryPostProcessor {
                    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                        beanFactory.getBeanNamesForType(Handler.class);
                    }
                }
                class Launcher {
                    public static void main(String[] args) {
                        ConfigurableApplicationContext context = SpringApplication.run(Launcher.class, args);
                        context.getBean(Runner.class).run();
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new ManualBeanLookupRule(), code);

        // Получатель узнается по объявленному типу, по вызову getApplicationContext() и по имени класса-держателя.
        // Конфигурация, часть настройки контейнера и main не считаются
        assertThat(violations).extracting(Violation::line).containsExactly(6, 7, 9, 10, 12);
        assertThat(violations.get(0).message()).contains("инверсии управления").contains("Map<String, T>");
        assertThat(violations.get(1).message()).contains("List<T>");
        assertThat(violations.get(2).message()).contains("мимо контейнера");
        assertThat(violations.get(3).message()).contains("ObjectProvider<T>");
    }

    @Test
    void containerInStaticFieldIsReported() {
        String code = """
                @Component
                public class SpringContextHolder implements ApplicationContextAware {
                    private static ApplicationContext context;
                    public void setApplicationContext(ApplicationContext applicationContext) {
                        context = applicationContext;
                    }
                    public static <T> T getBean(Class<T> type) {
                        return context.getBean(type);
                    }
                }
                @Service
                class Prices {
                    private final ApplicationContext context;
                    private static final Logger log = LoggerFactory.getLogger(Prices.class);
                }
                @SpringBootApplication
                class Application {
                    private static ConfigurableApplicationContext context;
                    public static void main(String[] args) {
                        context = SpringApplication.run(Application.class, args);
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(new StaticContainerHolderRule(), code);

        // Обычное поле и главный класс, который держит контекст для перезапуска, не считаются
        assertThat(violations).extracting(Violation::line).containsExactly(3);
        assertThat(violations.get(0).message()).contains("статический getBean").contains("инверсии управления");
        // Вызов getBean внутри самого держателя - забота jr:423, а не jr:164
        assertThat(RuleTests.check(new ManualBeanLookupRule(), code)).isEmpty();
    }
}
