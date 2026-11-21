package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.AnnotationValues;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Точка внедрения с {@code @Qualifier}, для которой бин с таким именем есть не во всех окружениях: все бины с этим
 * квалификатором закрыты {@code @Profile}, а сама точка внедрения от профиля не зависит. Там, где ни один из этих
 * профилей не включен, контекст не поднимается. Обычно бину под другой профиль просто забыли квалификатор
 */
@Component
public class QualifierWithoutBeanRule extends AbstractRule implements ProjectRule {
    private static final String QUALIFIER = "Qualifier";
    private static final String PROFILE = "Profile";
    private static final String BEAN = "Bean";
    private static final Set<String> STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration");
    private static final Set<String> NAME_MEMBERS = Set.of("value", "name");
    private static final Set<String> VALUE_MEMBER = Set.of("value");
    private static final Pattern CONDITIONAL = Pattern.compile("^Conditional.*");
    // Внедрение, которому бин не обязателен либо нужны все бины типа сразу
    private static final Set<String> OPTIONAL_TYPES = Set.of(
            "Optional", "ObjectProvider", "Provider", "ObjectFactory", "List", "Set", "Collection", "Map");
    private static final Set<String> OPTIONAL_ANNOTATIONS = Set.of("Nullable", "Lazy");
    private static final Pattern NOT_REQUIRED = Pattern.compile("required\\s*=\\s*false");
    private static final String NEGATION = "!";
    // dev, !local; выражения вида "a & b" не разбираются
    private static final Pattern SIMPLE_PROFILE = Pattern.compile("^!?[\\w.\\-]+$");
    private static final int MAX_NAMED = 2;

    /**
     * Когда объявление участвует в сборке контекста
     *
     * @param profiles профили из @Profile; пусто - всегда
     * @param unknown  есть условие, о котором по коду судить нельзя: @ConditionalOnProperty, составной профиль
     */
    private record Condition(List<String> profiles, boolean unknown) {

        private boolean always() {
            return profiles.isEmpty() && !unknown;
        }
    }

    /**
     * Бин, который подходит под квалификатор
     */
    private record Candidate(String owner, Condition condition) {
    }

    @Override
    public String code() {
        return RuleCodes.QUALIFIER_WITHOUT_BEAN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет @Qualifier, бин для которого объявлен только под отдельным профилем";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        Map<String, List<Candidate>> candidates = candidates(classes);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (AnnotationExpr qualifier : sourceFile.findAll(AnnotationExpr.class)) {
                if (!QUALIFIER.equals(qualifier.getName().getIdentifier()) || TestClasses.isInside(qualifier)) {
                    continue;
                }
                Optional<String> injectedType = injectedType(qualifier);
                List<String> names = AnnotationValues.keys(qualifier, VALUE_MEMBER, classes);
                if (injectedType.isEmpty() || names.isEmpty()) {
                    continue;
                }
                List<Candidate> named = candidates.getOrDefault(names.get(0), List.of());
                Optional<Set<String>> profiles = onlyUnderProfiles(named);
                if (profiles.isPresent()) {
                    violations.add(violation(sourceFile, qualifier,
                            describe(names.get(0), named, profiles.get(), injectedType.get(), classes)));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private String describe(
            String name, List<Candidate> named, Set<String> profiles, String injectedType, ProjectClasses classes) {
        Set<String> owners = new LinkedHashSet<>();
        named.forEach(candidate -> owners.add(candidate.owner()));
        // Бины того же типа без квалификатора: один из них, скорее всего, и должен был его получить
        List<String> siblings = classes.all().stream()
                .filter(type -> !type.isInterface() && Annotations.hasAny(type, STEREOTYPES))
                .filter(type -> !owners.contains(type.getNameAsString()) && isOfType(type, injectedType))
                .map(type -> type.getNameAsString())
                .sorted()
                .limit(MAX_NAMED)
                .toList();
        String advice = siblings.isEmpty()
                ? "объявите бин с этим квалификатором для остальных окружений"
                : "бин того же типа " + String.join(", ", siblings) + " квалификатора не несет - добавьте его";
        return "@Qualifier(\"" + name + "\"): бин с таким именем объявлен только под профилем "
                + String.join(", ", profiles) + " (" + String.join(", ", owners) + "), а место внедрения от"
                + " профиля не зависит - в окружении без этого профиля подходящего бина нет, и контекст не"
                + " поднимется (NoSuchBeanDefinitionException); " + advice;
    }

    private boolean isOfType(ClassOrInterfaceDeclaration type, String wanted) {
        return type.getNameAsString().equals(wanted)
                || type.getImplementedTypes().stream().anyMatch(parent -> parent.getNameAsString().equals(wanted))
                || type.getExtendedTypes().stream().anyMatch(parent -> parent.getNameAsString().equals(wanted));
    }

    /**
     * @return тип, который внедряют по квалификатору; пусто, если это не обязательное внедрение одного бина
     * либо место внедрения само работает не во всех окружениях
     */
    private Optional<String> injectedType(AnnotationExpr qualifier) {
        Node owner = qualifier.getParentNode().orElse(null);
        String type;
        NodeWithAnnotations<?> annotated;
        if (owner instanceof Parameter parameter) {
            type = LocalTypes.typeName(parameter.getType());
            annotated = parameter;
        } else if (owner instanceof FieldDeclaration field) {
            type = LocalTypes.typeName(field.getElementType());
            annotated = field;
        } else {
            return Optional.empty();
        }
        boolean optional = OPTIONAL_TYPES.contains(type) || Annotations.hasAny(annotated, OPTIONAL_ANNOTATIONS)
                || annotated.getAnnotations().stream().anyMatch(other -> NOT_REQUIRED.matcher(other.toString()).find());
        Optional<ClassOrInterfaceDeclaration> bean = owner.findAncestor(ClassOrInterfaceDeclaration.class);
        Optional<MethodDeclaration> method = owner.findAncestor(MethodDeclaration.class);
        boolean always = bean.filter(holder -> Annotations.hasAny(holder, STEREOTYPES) && conditionOf(holder).always())
                .isPresent()
                && method.map(this::conditionOf).map(Condition::always).orElse(true)
                && method.filter(setter -> setter.getAnnotations().stream()
                        .anyMatch(other -> NOT_REQUIRED.matcher(other.toString()).find()))
                .isEmpty();
        return optional || !always ? Optional.empty() : Optional.of(type);
    }

    // Имена, под которыми бин находят по квалификатору: сам @Qualifier, имя бина явное и по умолчанию
    private Map<String, List<Candidate>> candidates(ProjectClasses classes) {
        Map<String, List<Candidate>> candidates = new HashMap<>();
        for (ClassOrInterfaceDeclaration type : classes.all()) {
            Condition ofType = conditionOf(type);
            if (!type.isInterface() && Annotations.hasAny(type, STEREOTYPES)) {
                Set<String> names = new HashSet<>(names(type, STEREOTYPES, classes));
                names.addAll(names(type, Set.of(QUALIFIER), classes));
                names.add(decapitalize(type.getNameAsString()));
                names.forEach(name -> candidates.computeIfAbsent(name, key -> new ArrayList<>())
                        .add(new Candidate(type.getNameAsString(), ofType)));
            }
            for (MethodDeclaration method : type.getMethods()) {
                if (!Annotations.has(method, BEAN)) {
                    continue;
                }
                Set<String> names = new HashSet<>(names(method, Set.of(BEAN, QUALIFIER), classes));
                names.add(method.getNameAsString());
                Condition ofMethod = conditionOf(method);
                // Условия класса настроек и метода действуют вместе: два набора профилей сразу не разбираем
                Condition condition = ofMethod.always() ? ofType
                        : ofType.always() ? ofMethod
                        : new Condition(List.of(), true);
                String owner = type.getNameAsString() + "." + method.getNameAsString();
                names.forEach(name -> candidates.computeIfAbsent(name, key -> new ArrayList<>())
                        .add(new Candidate(owner, condition)));
            }
        }
        return candidates;
    }

    private Set<String> names(NodeWithAnnotations<?> node, Set<String> annotations, ProjectClasses classes) {
        Set<String> names = new HashSet<>();
        node.getAnnotations().stream()
                .filter(annotation -> annotations.contains(annotation.getName().getIdentifier()))
                .forEach(annotation -> names.addAll(AnnotationValues.keys(annotation, NAME_MEMBERS, classes)));
        return names;
    }

    private Condition conditionOf(NodeWithAnnotations<?> node) {
        List<String> profiles = new ArrayList<>();
        boolean unknown = false;
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String name = annotation.getName().getIdentifier();
            if (PROFILE.equals(name)) {
                List<String> values = AnnotationValues.expressions(annotation, VALUE_MEMBER).stream()
                        .map(value -> value.isStringLiteralExpr() ? value.asStringLiteralExpr().getValue() : "")
                        .toList();
                unknown = unknown || values.isEmpty()
                        || values.stream().anyMatch(value -> !SIMPLE_PROFILE.matcher(value).matches());
                profiles.addAll(values);
            } else if (CONDITIONAL.matcher(name).matches()) {
                unknown = true;
            }
        }
        return new Condition(profiles, unknown);
    }

    /**
     * @return профили, вне которых нет ни одного из бинов; пусто, если хотя бы один бин есть всегда, условие
     * какого-то из них неизвестно либо профили бинов дополняют друг друга (local и !local)
     */
    private Optional<Set<String>> onlyUnderProfiles(List<Candidate> named) {
        if (named.isEmpty() || named.stream().anyMatch(candidate -> candidate.condition().always()
                || candidate.condition().unknown())) {
            return Optional.empty();
        }
        Set<String> positive = new LinkedHashSet<>();
        Set<String> negative = new HashSet<>();
        for (Candidate candidate : named) {
            for (String profile : candidate.condition().profiles()) {
                if (profile.startsWith(NEGATION)) {
                    negative.add(profile.substring(NEGATION.length()));
                } else {
                    positive.add(profile);
                }
            }
        }
        boolean complementary = positive.stream().anyMatch(negative::contains);
        Set<String> all = new LinkedHashSet<>(positive);
        negative.forEach(profile -> all.add(NEGATION + profile));
        return complementary ? Optional.empty() : Optional.of(all);
    }

    private String decapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
