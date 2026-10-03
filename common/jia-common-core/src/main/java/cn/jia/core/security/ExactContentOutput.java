package cn.jia.core.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Preserves validated author-controlled content bytes while retaining recursive secret-field and
 * circular-reference sanitization. This is intentionally field-scoped; it must not be used for
 * identity, authorization, credential, error, or generic response-envelope fields.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ExactContentOutput {
    String reason();
}