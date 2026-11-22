package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.JavaOptsNotAppliedRule;
import ru.akvine.zond.rules.resources.DockerShellFormEntrypointRule;
import ru.akvine.zond.rules.security.DebugAgentInDeploymentRule;
import ru.akvine.zond.rules.security.HelmValuesSecretRule;
import ru.akvine.zond.rules.security.JmxRemoteWithoutProtectionRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:553 - jr:557: настройки развертывания - секреты в values-файлах Helm, отладочный агент и JMX без защиты,
 * параметры JVM, которые до нее не доходят, и запуск через оболочку
 */
class DeploymentSafetyRulesTest {
    private static final String CHART = "apiVersion: v2\nname: shop\nversion: 1.0.0\n";

    @Test
    void secretInHelmValuesIsFound(@TempDir Path project) {
        List<Violation> violations = new ProjectFixture(project)
                .write("deploy/helm/Chart.yaml", CHART)
                .write("deploy/helm/values.yaml", """
                        image:
                          repository: registry.example/shop
                          tag: null
                        database:
                          user: shop
                          password: "Qw7-pLz_29x"
                          existingSecret: shop-db
                        mail:
                          apiToken: {{ .Values.global.mailToken }}
                          tokenUri: https://auth.example/oauth/token
                        jmx:
                          password: null
                        env:
                          secret:
                            spring_datasource_password: s3cr3t-Value
                        clients:
                          - name: partner
                            clientSecret: 9f8e7d6c5b4a
                        """)
                .check(new HelmValuesSecretRule());
        assertThat(violations).extracting(Violation::line).containsExactly(6, 15, 18);
        assertThat(violations.get(0).message())
                .contains("'database.password'", "values-файле Helm", "existingSecret");
        assertThat(violations.get(1).message()).contains("'env.secret.spring_datasource_password'");
        assertThat(violations).allMatch(violation -> violation.confidenceOrDefault() == Confidence.PROBABLE);
    }

    @Test
    void secretOfDeveloperStandOrOutsideChartIsNotCertain(@TempDir Path project) {
        ProjectFixture fixture = new ProjectFixture(project)
                .write("chart/Chart.yaml", CHART)
                .write("chart/values-dev.yaml", "database:\n  password: Qw7-pLz_29x\n")
                .write("chart/values-prod.yaml", "database:\n  password: postgres\n")
                // Не чарт: рядом нет Chart.yaml
                .write("config/values.yaml", "database:\n  password: Qw7-pLz_29x\n");
        List<Violation> violations = fixture.check(new HelmValuesSecretRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName().toString())
                .containsExactly("values-dev.yaml", "values-prod.yaml");
        assertThat(violations).allMatch(violation -> violation.confidenceOrDefault() == Confidence.SUSPICION);
    }

    @Test
    void debugAgentOpenToNetworkIsFound(@TempDir Path project) {
        ProjectFixture fixture = new ProjectFixture(project)
                .write("chart/Chart.yaml", CHART)
                .write("chart/values.yaml", """
                        env:
                          java_opts:
                            -Dfile.encoding=UTF-8
                            -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5024
                        """)
                .write("chart/values-dev.yaml", """
                        env:
                          java_opts: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
                        """)
                .write("docker-compose.yml", """
                        services:
                          app:
                            image: shop:1.0
                            environment:
                              JAVA_TOOL_OPTIONS: "-agentlib:jdwp=transport=dt_socket,server=y,address=localhost:5005"
                              # -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
                          worker:
                            image: worker:1.0
                            command: java -Xrunjdwp:transport=dt_socket,server=n,address=debugger:5005 -jar worker.jar
                        """);
        List<Violation> violations = fixture.check(new DebugAgentInDeploymentRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactlyInAnyOrder("values.yaml:4", "values-dev.yaml:2");
        Violation production = violations.stream()
                .filter(violation -> violation.file().endsWith("values.yaml")).findFirst().orElseThrow();
        assertThat(production.errorLevel()).isEqualTo(ErrorLevel.CRITICAL);
        assertThat(production.message()).contains("address=0.0.0.0:5024", "произвольный код", "localhost");
        Violation development = violations.stream()
                .filter(violation -> violation.file().endsWith("values-dev.yaml")).findFirst().orElseThrow();
        assertThat(development.confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
    }

    @Test
    void debugAgentOnPortOnlyDependsOnJavaVersion(@TempDir Path projects) {
        String dockerfile = """
                FROM eclipse-temurin:17-jre
                COPY app.jar /app.jar
                ENTRYPOINT ["java", "-agentlib:jdwp=transport=dt_socket,server=y,address=5005", "-jar", "/app.jar"]
                """;
        String pom = """
                <project>
                  <properties>
                    <java.version>%s</java.version>
                  </properties>
                </project>
                """;
        DebugAgentInDeploymentRule rule = new DebugAgentInDeploymentRule();
        // С Java 9 адрес из одного порта - только сама машина
        assertThat(new ProjectFixture(projects.resolve("modern"))
                .write("Dockerfile", dockerfile).write("pom.xml", pom.formatted("17")).check(rule)).isEmpty();
        List<Violation> old = new ProjectFixture(projects.resolve("old"))
                .write("Dockerfile", dockerfile).write("pom.xml", pom.formatted("8")).check(rule);
        assertThat(old).extracting(Violation::line).containsExactly(3);
        assertThat(old.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        assertThat(old.get(0).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        // Версия Java неизвестна - только подозрение
        assertThat(new ProjectFixture(projects.resolve("unknown")).write("Dockerfile", dockerfile).check(rule))
                .extracting(Violation::confidenceOrDefault).containsExactly(Confidence.SUSPICION);
    }

    @Test
    void remoteJmxWithoutProtectionIsFound(@TempDir Path projects) {
        JmxRemoteWithoutProtectionRule rule = new JmxRemoteWithoutProtectionRule();
        List<Violation> open = new ProjectFixture(projects.resolve("open"))
                .write("docker-compose.yml", """
                        services:
                          app:
                            image: shop:1.0
                            environment:
                              JAVA_TOOL_OPTIONS: >-
                                -Dcom.sun.management.jmxremote.port=9010
                                -Dcom.sun.management.jmxremote.authenticate=false
                                -Dcom.sun.management.jmxremote.ssl=false
                        """)
                .check(rule);
        assertThat(open).extracting(Violation::line).containsExactly(7);
        assertThat(open.get(0).errorLevel()).isEqualTo(ErrorLevel.CRITICAL);
        assertThat(open.get(0).message()).contains("без аутентификации");

        List<Violation> plain = new ProjectFixture(projects.resolve("plain"))
                .write("chart/Chart.yaml", CHART)
                .write("chart/values.yaml", """
                        env:
                          java_opts:
                            -Dcom.sun.management.jmxremote.port=5034
                            -Dcom.sun.management.jmxremote.ssl=false
                            -Dcom.sun.management.jmxremote.authenticate=true
                        """)
                .check(rule);
        assertThat(plain).extracting(Violation::line).containsExactly(4);
        assertThat(plain.get(0).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(plain.get(0).message()).contains("без TLS", "открытым текстом");
    }

    @Test
    void localJmxAndProtectedJmxAreFine(@TempDir Path projects) {
        JmxRemoteWithoutProtectionRule rule = new JmxRemoteWithoutProtectionRule();
        // Порт не задан: JMX доступен только с самой машины
        assertThat(new ProjectFixture(projects.resolve("local"))
                .write("Dockerfile", """
                        FROM eclipse-temurin:17-jre
                        ENV JAVA_TOOL_OPTIONS="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.ssl=false"
                        ENTRYPOINT ["java", "-jar", "/app.jar"]
                        """)
                .check(rule)).isEmpty();
        assertThat(new ProjectFixture(projects.resolve("protected"))
                .write("Dockerfile", """
                        FROM eclipse-temurin:17-jre
                        ENV JMX_PORT="-Dcom.sun.management.jmxremote.port=9010"
                        ENV JAVA_TOOL_OPTIONS="$JMX_PORT -Dcom.sun.management.jmxremote.ssl=true"
                        ENTRYPOINT ["java", "-jar", "/app.jar"]
                        """)
                .check(rule)).isEmpty();
    }

    private static final String DIRECT_START = """
            FROM eclipse-temurin:17-jre
            COPY target/app.jar /app.jar
            ENTRYPOINT ["java", "-jar", "/app.jar"]
            """;

    @Test
    void javaOptsThatStartCommandDoesNotUseIsFound(@TempDir Path projects) {
        JavaOptsNotAppliedRule rule = new JavaOptsNotAppliedRule();
        List<Violation> violations = new ProjectFixture(projects.resolve("manifest"))
                .write("Dockerfile", DIRECT_START)
                .write("k8s/deployment.yaml", """
                        apiVersion: apps/v1
                        kind: Deployment
                        metadata:
                          name: shop
                        spec:
                          template:
                            spec:
                              containers:
                                - name: shop
                                  image: shop:1.0
                                  env:
                                    - name: JAVA_OPTS
                                      value: "-Xmx512m -Dfile.encoding=UTF-8"
                        """)
                .check(rule);
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("deployment.yaml:12");
        assertThat(violations.get(0).message())
                .contains("JAVA_OPTS", "ENTRYPOINT [\"java\", \"-jar\", \"/app.jar\"]", "Dockerfile:3")
                .contains("JAVA_TOOL_OPTIONS");

        // Переменная задана в самом Dockerfile и в values-файле чарта, где шаблон собирает переменные циклом
        assertThat(new ProjectFixture(projects.resolve("chart"))
                .write("Dockerfile", """
                        FROM eclipse-temurin:17-jre
                        ENV JAVA_OPTS="-Xmx512m"
                        ENTRYPOINT java -jar /app/app.jar
                        """)
                .write("chart/Chart.yaml", CHART)
                .write("chart/values.yaml", "env:\n  plain:\n    java_opts: -Xmx1g\n")
                .lines(rule)).containsExactly("Dockerfile:2", "values.yaml:3");
    }

    @Test
    void javaOptsThatSomeoneSubstitutesIsFine(@TempDir Path projects) {
        JavaOptsNotAppliedRule rule = new JavaOptsNotAppliedRule();
        String compose = """
                services:
                  app:
                    image: shop:1.0
                    environment:
                      - JAVA_OPTS=-Xmx512m
                """;
        // Команда подставляет переменную сама
        assertThat(new ProjectFixture(projects.resolve("shell"))
                .write("Dockerfile", """
                        FROM eclipse-temurin:17-jre
                        ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app.jar"]
                        """)
                .write("docker-compose.yml", compose).check(rule)).isEmpty();
        // Запуск через сценарий: что он делает с переменной, не видно
        assertThat(new ProjectFixture(projects.resolve("script"))
                .write("Dockerfile", "FROM eclipse-temurin:17-jre\nENTRYPOINT [\"/entrypoint.sh\"]\n")
                .write("docker-compose.yml", compose).check(rule)).isEmpty();
        // Образ собирают не здесь
        assertThat(new ProjectFixture(projects.resolve("external")).write("docker-compose.yml", compose).check(rule))
                .isEmpty();
        // JAVA_TOOL_OPTIONS JVM читает сама
        assertThat(new ProjectFixture(projects.resolve("tool"))
                .write("Dockerfile", DIRECT_START)
                .write("docker-compose.yml", compose.replace("JAVA_OPTS", "JAVA_TOOL_OPTIONS")).check(rule)).isEmpty();
        // Манифест заменяет команду образа своей и подставляет переменную
        assertThat(new ProjectFixture(projects.resolve("command"))
                .write("Dockerfile", DIRECT_START)
                .write("docker-compose.yml", compose + "    command: sh -c \"java $JAVA_OPTS -jar /app.jar\"\n")
                .check(rule)).isEmpty();
        // Ключ values вставлен шаблоном прямо в команду
        assertThat(new ProjectFixture(projects.resolve("args"))
                .write("Dockerfile", DIRECT_START)
                .write("chart/Chart.yaml", CHART)
                .write("chart/values.yaml", "javaOpts: -Xmx1g\n")
                .write("chart/templates/deployment.yaml", """
                        apiVersion: apps/v1
                        kind: Deployment
                        spec:
                          template:
                            spec:
                              containers:
                                - name: shop
                                  args: ["{{ .Values.javaOpts }}"]
                        """)
                .check(rule)).isEmpty();
    }

    @Test
    void jvmStartedThroughShellIsFound(@TempDir Path projects) {
        DockerShellFormEntrypointRule rule = new DockerShellFormEntrypointRule();
        List<Violation> violations = new ProjectFixture(projects.resolve("shell"))
                .write("Dockerfile", """
                        FROM eclipse-temurin:17-jre
                        ARG APP_NAME=shop
                        ENV APP=$APP_NAME
                        COPY target/$APP_NAME.jar /app/$APP_NAME.jar
                        ENTRYPOINT java -jar /app/$APP.jar
                        """)
                .check(rule);
        assertThat(violations).extracting(Violation::line).containsExactly(5);
        assertThat(violations.get(0).message()).contains("/bin/sh -c", "SIGTERM", "exec");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(new ProjectFixture(projects.resolve("cmd"))
                .write("Dockerfile", """
                        FROM eclipse-temurin:17-jre
                        CMD JAVA_HOME=/opt/java /opt/java/bin/java -jar /app.jar
                        """)
                .check(rule)).extracting(Violation::line).containsExactly(2);
    }

    @Test
    void jvmThatBecomesFirstProcessIsFine(@TempDir Path projects) {
        DockerShellFormEntrypointRule rule = new DockerShellFormEntrypointRule();
        for (String start : List.of(
                "ENTRYPOINT [\"java\", \"-jar\", \"/app.jar\"]",
                "ENTRYPOINT exec java $JAVA_OPTS -jar /app.jar",
                "ENTRYPOINT [\"sh\", \"-c\", \"exec java $JAVA_OPTS -jar /app.jar\"]",
                "ENTRYPOINT /entrypoint.sh",
                // ENTRYPOINT важнее CMD: команду запускает он
                "CMD java -jar /app.jar\nENTRYPOINT [\"/usr/bin/tini\", \"--\"]")) {
            assertThat(new ProjectFixture(projects.resolve("ok" + Math.abs(start.hashCode())))
                    .write("Dockerfile", "FROM eclipse-temurin:17-jre\n" + start + "\n")
                    .check(rule)).as(start).isEmpty();
        }
    }
}
