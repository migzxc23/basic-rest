package com.miguelrecido.secrets;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Marks an application as using AWS Secrets Manager for its credentials.
 * <p>
 * The actual loading happens very early (as an {@code EnvironmentPostProcessor}, before the
 * DataSource is created) and is switched on by {@code secrets.manager.enabled=true}.
 * This annotation registers a startup report bean so the adoption is visible in the codebase and logs.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(SecretsManagerMarkerConfiguration.class)
public @interface EnableSecretsManager {
}
