package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;

@UtilityClass
public class RuleCodes {

    public final static String CHECK_TRANSACTION_ON_PRIVATE_METHOD_RULE_CODE = "jr:1";
    public final static String CHECK_TRANSACTIONAL_SELF_INVOCATION_RULE_CODE = "jr:2";
    public final static String CHECK_TRANSACTIONAL_ROLLBACK_FOR_CHECKED_EXCEPTION_RULE_CODE = "jr:3";
    public final static String CHECK_AUTOWIRED_ON_STATIC_FIELD_RULE_CODE = "jr:4";
    public final static String CHECK_TRANSACTIONAL_ON_CONTROLLER_RULE_CODE = "jr:5";
    public final static String CHECK_FIELD_INJECTION_RULE_CODE = "jr:6";
    public final static String CHECK_MUTABLE_STATE_IN_SINGLETON_BEAN_RULE_CODE = "jr:7";
    public final static String CHECK_WRITE_IN_READ_ONLY_TRANSACTION_RULE_CODE = "jr:8";
    public final static String CHECK_STATIC_SIMPLE_DATE_FORMAT_RULE_CODE = "jr:9";
    public final static String CHECK_LOCAL_DATE_TIME_NOW_RULE_CODE = "jr:10";
    public final static String CHECK_PERIOD_BETWEEN_FOR_EXACT_INTERVAL_RULE_CODE = "jr:11";
    public final static String CHECK_DURATION_OF_DAYS_AS_YEAR_RULE_CODE = "jr:12";
    public final static String CHECK_DATE_TIME_FORMATTER_LOCALE_RULE_CODE = "jr:13";
}
