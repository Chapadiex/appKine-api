package com.akine.assistant.infrastructure.config;

import com.akine.assistant.application.AuditoriaDelAsistente;
import com.akine.assistant.application.ConsultaSqlHerramienta;
import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.port.LectorDeVistas;
import com.akine.assistant.infrastructure.JdbcLectorDeVistas;
import com.akine.organization.spi.PermissionEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Cableado de {@code consulta_sql} y {@code consulta_sql_clinica}. Los beans existen siempre: que se
 * ofrezcan lo decide {@code disponiblePara} ({@code akine.asistente.sql.enabled} y el permiso).
 */
@Configuration
class ConsultaSqlConfig {

	static final String PROMPT_SQL = "asistente/prompt-sql.txt";
	static final String PROMPT_SQL_CLINICO = "asistente/prompt-sql-clinico.txt";

	@Bean
	LectorDeVistas lectorDeVistasDelAsistente(JdbcTemplate jdbc, PlatformTransactionManager transacciones,
			AsistenteProperties properties) {
		return new JdbcLectorDeVistas(jdbc, transacciones, properties.sql().tiempoMaximoMs());
	}

	@Bean
	ConsultaSqlHerramienta consultaSql(LectorDeVistas lector, AuditoriaDelAsistente auditoria,
			PermissionEvaluator permisos, AsistenteAjustes ajustes) {
		return new ConsultaSqlHerramienta(false, AsistenteConfig.leerPrompt(PROMPT_SQL), lector, auditoria, permisos,
				ajustes);
	}

	@Bean
	ConsultaSqlHerramienta consultaSqlClinica(LectorDeVistas lector, AuditoriaDelAsistente auditoria,
			PermissionEvaluator permisos, AsistenteAjustes ajustes) {
		return new ConsultaSqlHerramienta(true, AsistenteConfig.leerPrompt(PROMPT_SQL_CLINICO), lector, auditoria,
				permisos, ajustes);
	}
}
