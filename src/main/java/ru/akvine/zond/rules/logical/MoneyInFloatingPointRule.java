package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.SimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MoneyInFloatingPointRule extends AbstractRule implements ProjectRule {
    private static final String AMOUNT = "amount";
    // LoanApiResourceSwagger.GetLoansResponse: класс только описывает JSON для OpenAPI, денег в нем не считают
    private static final String SCHEMA = "Schema";
    private static final Pattern DOCUMENTATION_CLASS = Pattern.compile(".*(Swagger|ApiDocs?|Documentation)$");
    private static final String PRICE = "price";
    private static final Set<String> FLOATING_TYPES = Set.of("double", "float", "Double", "Float");

    // Типы не разрешаем: о том, что в поле деньги, судим по имени
    private static final Pattern MONEY_NAME = Pattern.compile(
            ".*(price|amount|cost|balance|salary|fee|payment|money|revenue|discount|tax).*|^(total|sum)$",
            Pattern.CASE_INSENSITIVE);
    // Слова, которые встречаются и вне денег: стоимость запроса в планировщике (MATCH_COST), баланс нагрузки,
    // скидка в сглаживании (StupidBackoff.discount), сумма гистограммы. Деньги ли это, решает остальной файл
    // либо проект: в банковской системе amount у TellerTransaction - сумма, хотя в самом файле о деньгах ни слова
    private static final Pattern AMBIGUOUS_NAME =
            Pattern.compile(".*(cost|balance|discount|amount).*|^(total|sum)$", Pattern.CASE_INSENSITIVE);
    // Слова имени сравниваем целиком: tax в syntax и fee в feedback - не деньги
    private static final Set<String> MONEY_WORDS = Set.of(
            "price", "prices", "currency", "money", "payment", "payments", "invoice", "salary", "tax", "taxes",
            "revenue", "billing", "wallet", "cash", "budget", "fee", "fees", "refund", "checkout", "loan", "credit",
            "debit", "deposit", "bank", "finance", "financial", "purchase", "cart", "monetary");
    private static final Set<String> MONEY_TYPES = Set.of("BigDecimal", "Currency", "MonetaryAmount", "Money");
    private static final String BIG_DECIMAL = "BigDecimal";
    // Доля файлов проекта со словами о деньгах: в fineract и shopizer это десятки процентов, в Elasticsearch
    // и Bazel - доли процента
    private static final double MONEY_PROJECT_SHARE = 0.02;
    private static final Pattern WORD_BOUNDARY = Pattern.compile("_|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    // Коэффициент, а не сумма: indexBalanceFactor, RERANK_COST_WEIGHT, discountRatio
    private static final Pattern COEFFICIENT = Pattern.compile(
            ".*(factor|weight|ratio|coef|multiplier|threshold|percent|score|penalty|boost).*",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.MONEY_IN_FLOATING_POINT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет денежные поля типа double и float";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        long moneyFiles = sourceFiles.stream().filter(sourceFile -> hasMoneyContext(sourceFile.unit(), false)).count();
        boolean moneyProject = moneyFiles >= Math.max(1, sourceFiles.size() * MONEY_PROJECT_SHARE);
        return sourceFiles.stream().flatMap(sourceFile -> check(sourceFile, moneyProject).stream()).toList();
    }

    private List<Violation> check(SourceFile sourceFile, boolean moneyProject) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            if (isApiDocumentation(field)) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                String type = LocalTypes.typeName(variable.getType());
                String name = variable.getNameAsString();
                if (FLOATING_TYPES.contains(type) && MONEY_NAME.matcher(name).matches()
                        && !COEFFICIENT.matcher(name).matches() && !isQuantity(variable, field)
                        && (!AMBIGUOUS_NAME.matcher(name).matches() || moneyProject
                        || hasMoneyContext(sourceFile.unit(), true))) {
                    violations.add(violation(sourceFile, variable,
                            "Денежное поле '" + variable.getNameAsString() + "' типа " + type + ": двоичная дробь"
                                    + " не может точно представить 0.1, при сложении и округлении копейки"
                                    + " расходятся; используйте BigDecimal либо целое число минимальных единиц"));
                }
            }
        }
        return violations;
    }

    // В файле есть что-то еще про деньги: цена, валюта, платеж, BigDecimal. По проекту BigDecimal не считаем:
    // им пользуются и для обычной точной арифметики
    private boolean hasMoneyContext(CompilationUnit unit, boolean withBigDecimal) {
        return unit.findAll(SimpleName.class).stream()
                .map(SimpleName::getIdentifier)
                .anyMatch(name -> MONEY_TYPES.contains(name) && (withBigDecimal || !BIG_DECIMAL.equals(name))
                        || Arrays.stream(WORD_BOUNDARY.split(name))
                        .anyMatch(word -> MONEY_WORDS.contains(word.toLowerCase(Locale.ROOT))));
    }

    // amount рядом с ценой - это количество (2.5 кг, 0.5 порции), а деньги лежат в price
    // Класс с @Schema внутри LoanApiResourceSwagger: его экземпляров не создают, по нему строится описание API
    private boolean isApiDocumentation(FieldDeclaration field) {
        Optional<ClassOrInterfaceDeclaration> owner = field.findAncestor(ClassOrInterfaceDeclaration.class);
        Optional<ClassOrInterfaceDeclaration> outermost = owner;
        while (outermost.flatMap(type -> type.getParentNode())
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration).isPresent()) {
            outermost = outermost.flatMap(type -> type.getParentNode()).map(parent -> (ClassOrInterfaceDeclaration) parent);
        }
        return owner.filter(type -> Annotations.has(type, SCHEMA)).isPresent()
                && outermost.filter(type -> DOCUMENTATION_CLASS.matcher(type.getNameAsString()).matches()).isPresent();
    }

    private boolean isQuantity(VariableDeclarator variable, FieldDeclaration field) {
        if (!AMOUNT.equalsIgnoreCase(variable.getNameAsString())) {
            return false;
        }
        return field.getParentNode()
                .map(owner -> owner.findAll(VariableDeclarator.class).stream()
                        .anyMatch(other -> other != variable && other.getNameAsString().toLowerCase(Locale.ROOT).contains(PRICE)))
                .orElse(false);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
