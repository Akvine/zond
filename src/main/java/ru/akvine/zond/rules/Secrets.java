package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

/**
 * Типы не разрешаем, поэтому о том, что значение секретное, судим по имени переменной, поля или свойства
 */
@UtilityClass
class Secrets {
    private final static Pattern SECRET_NAME = Pattern.compile(
            ".*(password|passwd|pwd|secret|token|api[_.-]?key|private[_.-]?key|access[_.-]?key|credential|cvv"
                    + "|card[_.-]?number|pin[_.-]?code).*",
            Pattern.CASE_INSENSITIVE);

    // passwordEncoder, tokenService, token-uri, password.min-length: имя про секрет, но самого секрета там нет
    private final static Pattern NOT_SECRET_NAME = Pattern.compile(
            ".*(header|param|parameter|field|name|prefix|suffix|path|url|uri|property|attribute|claim|type|label"
                    + "|message|pattern|regex|format|column|cookie|code|error|words|id|length|size|count|policy"
                    + "|expired|expiration|validity|valid|ttl|timeout|duration|enabled|required|algorithm|issuer"
                    + "|encoder|service|repository|provider|filter|manager|generator|validator|store|utils?"
                    + "|factory|resolver|handler|dto|request|response|form)$",
            Pattern.CASE_INSENSITIVE);

    boolean isSecretName(String name) {
        return SECRET_NAME.matcher(name).matches() && !NOT_SECRET_NAME.matcher(name).matches();
    }
}
