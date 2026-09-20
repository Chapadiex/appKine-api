package com.akine.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.akine.platform.spi.audit.VocabularioClinicoDeAuditoria;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * El vocabulario clinico de la auditoria.
 *
 * <p>Esta clase es una <b>lista</b>, y una lista se olvida: un tipo de evento clinico nuevo cuyo
 * prefijo no este declarado se lee sin redactar y nada falla. Este test es el unico lugar donde
 * ese olvido puede verse, asi que enumera el vocabulario conocido a la fecha de AKINE-07.07.
 *
 * <p><b>Si agregas un evento clinico, agregalo aca tambien.</b>
 */
@DisplayName("El vocabulario clinico de la auditoria")
class VocabularioClinicoDeAuditoriaTest {

	@ParameterizedTest
	@ValueSource(strings = {
			// M09 — AKINE-04.01.
			"HISTORIA_CLINICA_OPENED",
			"HISTORIA_CLINICA_ACCESSED",
			"HISTORIA_CLINICA_RESUMEN_UPDATED",
			"ANTECEDENTE_CLINICO_REGISTERED",
			"ANTECEDENTE_CLINICO_DEACTIVATED",
			// M10 — AKINE-04.02.
			"TIMELINE_ACCESSED",
			"ENTRADA_CLINICA_REGISTERED",
			"ENTRADA_CLINICA_AMENDED",
			"ENTRADA_CLINICA_DEACTIVATED",
			"ADJUNTO_CLINICO_UPLOADED",
			"ADJUNTO_CLINICO_DOWNLOADED",
			"ADJUNTO_CLINICO_RECLASSIFIED",
			"ADJUNTO_CLINICO_DEACTIVATED",
			// M11 — AKINE-04.03.
			"CASO_CLINICO_OPENED",
			"CASO_CLINICO_ACCESSED",
			"CASO_CLINICO_UPDATED",
			"CASO_CLINICO_CLOSED",
			"CASO_CLINICO_REOPENED",
			"CASO_EQUIPO_CHANGED",
			// M13 — AKINE-04.04 y 04.05.
			"PLAN_TRATAMIENTO_CREATED",
			"PLAN_TRATAMIENTO_ACCESSED",
			"PLAN_TRATAMIENTO_UPDATED",
			"PLAN_TRATAMIENTO_ACTIVATED",
			"PLAN_TRATAMIENTO_SUSPENDED",
			"PLAN_TRATAMIENTO_RESUMED",
			"PLAN_TRATAMIENTO_FINALIZED",
			"PLAN_TRATAMIENTO_AMENDED",
			"PLAN_ITEM_AUTORIZACION_LINKED"
	})
	@DisplayName("reconoce cada evento que el modulo clinico emite hoy")
	void reconoceElVocabularioConocido(String eventType) {
		assertThat(VocabularioClinicoDeAuditoria.esClinico(eventType)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"MEMBERSHIP_REVOKED",
			"ORGANIZATION_CREATED",
			"CONSULTORIO_DEACTIVATED",
			"PERMISSION_DENIED",
			"SUPPORT_ACCESS_USED",
			"COBRO_REGISTERED",
			"CAJA_CLOSED",
			"TURNO_CANCELLED"
	})
	@DisplayName("y no se pasa de largo con los que no lo son")
	void noRedactaLoQueNoEsClinico(String eventType) {
		// Redactar de mas volveria inutil la pantalla para lo que si le compete a un
		// administrador. SUPPORT_ACCESS_USED esta a proposito en esta lista: es el rastro de
		// que alguien uso soporte, y taparlo seria taparle el ojo a quien vigila.
		assertThat(VocabularioClinicoDeAuditoria.esClinico(eventType)).isFalse();
	}

	@Test
	@DisplayName("y trata la ausencia de tipo como no clinica, que es como llega de una fila vieja")
	void nullNoRompe() {
		assertThat(VocabularioClinicoDeAuditoria.esClinico(null)).isFalse();
		assertThat(VocabularioClinicoDeAuditoria.esClinico("")).isFalse();
		assertThat(VocabularioClinicoDeAuditoria.esClinico("   ")).isFalse();
	}
}
