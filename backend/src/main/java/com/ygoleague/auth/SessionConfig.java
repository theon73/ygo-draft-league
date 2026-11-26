package com.ygoleague.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;

/**
 * Sessions live in Postgres (Spring Session JDBC, tables from {@code V6__http_sessions.sql}), so
 * a sign-in survives backend restarts and deploys. Lifetimes and cookie flags are in
 * {@code application.yml} under {@code server.servlet.session}.
 *
 * <p>Session attributes are Java-serialized. A deploy that changes a stored class incompatibly
 * (our {@link CurrentUser}, or a Spring Security upgrade) would make old sessions unreadable, and
 * by default that fails every request carrying such a cookie with a 500. This converter instead
 * treats an unreadable attribute as absent, so the user just appears signed out and signs in again.
 */
@Configuration(proxyBeanMethods = false)
class SessionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

    /** Picked up by name by Spring Session's JDBC configuration. */
    @Bean("springSessionConversionService")
    GenericConversionService springSessionConversionService() {
        DeserializingConverter deserializer = new DeserializingConverter(SessionConfig.class.getClassLoader());
        GenericConversionService conversions = new GenericConversionService();
        conversions.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversions.addConverter(byte[].class, Object.class, bytes -> {
            try {
                return deserializer.convert(bytes);
            } catch (RuntimeException e) {
                log.info("Dropping an unreadable session attribute (likely from an older deploy): {}", e.getMessage());
                return null;
            }
        });
        return conversions;
    }
}
