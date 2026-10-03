package ru.akvine.zond.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum ErrorType {
    UNKNOWN(null, null),
    CONCURRENCY("concurrency", "Ошибки параллелизма"),
    LOGICAL("logical", "Логические ошибки"),
    EXCEPTION("exception", "Исключения"),
    STREAM("stream", "Стримы"),
    DATE_AND_TIME("date and time", "Дата и время"),
    RESOURCE("resource", "Ресурсы"),
    PERFORMANCE("performance", "Производительность"),
    CODE_SMELL("code smell", "Качество кода"),
    SECURITY("security", "Безопасность");

    private final String code;
    private final String description;
}
