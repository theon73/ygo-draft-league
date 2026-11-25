package com.ygoleague.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PublicIdConfig {

    @Bean
    PublicIds publicIds(@Value("${app.public-ids.alphabet}") String alphabet,
                        @Value("${app.public-ids.min-length}") int minLength) {
        return new PublicIds(alphabet, minLength);
    }
}
