package com.akine.knowledge.infrastructure.config;

import com.akine.knowledge.domain.BibliotecaAjustes;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Cableado del modulo {@code knowledge}. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(BibliotecaProperties.class)
public class KnowledgeConfig {

	@Bean
	public BibliotecaAjustes bibliotecaAjustes(BibliotecaProperties properties) {
		return new BibliotecaAjustes(properties.enabled(), properties.maxBytes());
	}
}
