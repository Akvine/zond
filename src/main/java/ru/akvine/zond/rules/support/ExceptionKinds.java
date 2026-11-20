package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.type.ReferenceType;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.Set;

/**
 * Проверяемое исключение или нет. Точный ответ дает иерархия классов; когда ее разрешить не удалось (нет
 * библиотеки в classpath), исключение узнается по имени, а если имя незнакомо, ответ - "неизвестно":
 * считать такое исключение проверяемым нельзя, большинство исключений в прикладном коде - наследники
 * RuntimeException
 */
@UtilityClass
public class ExceptionKinds {
    public enum Kind {
        CHECKED, UNCHECKED, UNKNOWN
    }

    private static final Set<String> UNCHECKED_ROOTS = Set.of("RuntimeException", "Error");
    private static final String RUNTIME_SUFFIX = "RuntimeException";
    private static final String ERROR_SUFFIX = "Error";

    // JDK, Spring, Jakarta и распространенные библиотеки: наследники RuntimeException
    private static final Set<String> UNCHECKED = Set.of(
            "RuntimeException", "Error", "IllegalArgumentException", "IllegalStateException", "NullPointerException",
            "UnsupportedOperationException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException",
            "StringIndexOutOfBoundsException", "ClassCastException", "ArithmeticException", "NumberFormatException",
            "ConcurrentModificationException", "NoSuchElementException", "UncheckedIOException", "DateTimeException",
            "DateTimeParseException", "SecurityException", "CompletionException", "CancellationException",
            "IllegalMonitorStateException", "NegativeArraySizeException", "ArrayStoreException",
            "MissingResourceException", "EnumConstantNotPresentException", "TypeNotPresentException",
            "InputMismatchException", "IllegalFormatException", "UnsupportedTemporalTypeException",
            // Spring
            "DataAccessException", "DataIntegrityViolationException", "EmptyResultDataAccessException",
            "IncorrectResultSizeDataAccessException", "DuplicateKeyException", "OptimisticLockingFailureException",
            "ObjectOptimisticLockingFailureException", "PessimisticLockingFailureException",
            "CannotAcquireLockException", "TransactionException", "TransactionSystemException",
            "UnexpectedRollbackException", "ResponseStatusException", "RestClientException",
            "HttpClientErrorException", "HttpServerErrorException", "HttpStatusCodeException",
            "ResourceAccessException", "WebClientResponseException", "WebClientRequestException", "BeansException",
            "NoSuchBeanDefinitionException", "BeanCreationException", "HttpMessageNotReadableException",
            "HttpMessageConversionException", "ConversionFailedException", "MailException", "MessagingException",
            "AmqpException", "KafkaException", "AuthenticationException", "UsernameNotFoundException",
            "BadCredentialsException", "DisabledException", "LockedException", "AccountExpiredException",
            "CredentialsExpiredException", "InsufficientAuthenticationException",
            "AuthenticationCredentialsNotFoundException", "AuthenticationServiceException",
            "InternalAuthenticationServiceException", "OAuth2AuthenticationException", "AuthorizationDeniedException",
            "InvalidDataAccessApiUsageException", "MaxUploadSizeExceededException", "MultipartException",
            // Jakarta / javax, Hibernate
            "PersistenceException", "EntityNotFoundException", "EntityExistsException", "NoResultException",
            "NonUniqueResultException", "OptimisticLockException", "PessimisticLockException", "RollbackException",
            "TransactionRequiredException", "ConstraintViolationException", "ValidationException",
            "WebApplicationException", "HibernateException", "LazyInitializationException", "StaleObjectStateException",
            // Распространенные библиотеки
            "JwtException", "ExpiredJwtException", "FeignException", "CallNotPermittedException",
            "RequestNotPermitted", "JsonParseException", "JedisException", "RedisException", "MongoException",
            "UncheckedExecutionException", "UndeclaredThrowableException", "UncheckedTimeoutException");

    // Проверяемые исключения JDK и распространенных библиотек
    private static final Set<String> CHECKED = Set.of(
            "Exception", "Throwable", "IOException", "FileNotFoundException", "EOFException",
            "UnsupportedEncodingException", "MalformedURLException", "URISyntaxException", "SocketException",
            "SocketTimeoutException", "UnknownHostException", "ConnectException", "NoSuchFileException",
            "FileAlreadyExistsException", "SQLException", "SQLTimeoutException", "InterruptedException",
            "TimeoutException", "ExecutionException", "BrokenBarrierException", "ClassNotFoundException",
            "CloneNotSupportedException", "ReflectiveOperationException", "IllegalAccessException",
            "InvocationTargetException", "NoSuchMethodException", "NoSuchFieldException", "InstantiationException",
            "GeneralSecurityException", "NoSuchAlgorithmException", "InvalidKeyException", "InvalidKeySpecException",
            "KeyStoreException", "CertificateException", "UnrecoverableKeyException", "SignatureException",
            "NoSuchPaddingException", "BadPaddingException", "IllegalBlockSizeException",
            "InvalidAlgorithmParameterException", "KeyManagementException", "ParseException", "DataFormatException",
            "JsonProcessingException", "JsonMappingException", "JAXBException", "SAXException",
            "ParserConfigurationException", "TransformerException", "XMLStreamException", "XPathExpressionException",
            "NamingException", "JMSException", "TemplateException", "ServletException", "MessageRemovedException",
            "AWTException", "PrinterException", "ScriptException", "BackingStoreException", "TooManyListenersException",
            "JRException", "DocumentException", "WriterException", "MqttException", "SchedulerException",
            "JobExecutionException", "MethodArgumentNotValidException", "BindException",
            "HttpRequestMethodNotSupportedException",
            "HttpMediaTypeNotSupportedException", "MissingServletRequestParameterException", "NoHandlerFoundException");

    /**
     * @param exception тип из throws либо из catch
     */
    public Kind of(ReferenceType exception) {
        Optional<Boolean> resolved = Types.isKindOf(exception, UNCHECKED_ROOTS);
        if (resolved.isPresent()) {
            return resolved.get() ? Kind.UNCHECKED : Kind.CHECKED;
        }
        return ofName(simpleName(exception));
    }

    /**
     * @param name простое имя класса исключения
     */
    public Kind ofName(String name) {
        if (UNCHECKED.contains(name) || name.endsWith(RUNTIME_SUFFIX) || name.endsWith(ERROR_SUFFIX)) {
            return Kind.UNCHECKED;
        }
        return CHECKED.contains(name) ? Kind.CHECKED : Kind.UNKNOWN;
    }

    // java.io.IOException -> IOException
    private String simpleName(ReferenceType type) {
        return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
    }
}
