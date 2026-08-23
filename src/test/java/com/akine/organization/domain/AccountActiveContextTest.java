package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el puntero de contexto activo (RF-M01-005, ADR-0009).
 *
 * <p>El valor de retorno de {@link AccountActiveContext#pointTo(Long, Long)} es lo unico con
 * logica real: dice si el puntero cambio, y el llamador lo usa para no escribir un evento de
 * auditoria cuando el usuario reselecciona lo mismo. Un PUT repetido no puede ensuciar el
 * historial.
 */
class AccountActiveContextTest {

	private static final Long CUENTA = 21L;
	private static final Long ORG = 3L;
	private static final Long SEDE = 11L;

	private AccountActiveContext nuevoPuntero() {
		return new AccountActiveContext(CUENTA, ORG, SEDE);
	}

	@Test
	@DisplayName("El puntero nuevo guarda la seleccion inicial")
	void guarda_la_seleccion_inicial() {
		AccountActiveContext puntero = nuevoPuntero();

		assertThat(puntero.getAccountId()).isEqualTo(CUENTA);
		assertThat(puntero.getOrganizationId()).isEqualTo(ORG);
		assertThat(puntero.getConsultorioId()).isEqualTo(SEDE);
		assertThat(puntero.getId()).isNull();
		// updated_at lo pone el callback de JPA, no el constructor: la fila todavia no se
		// escribio.
		assertThat(puntero.getUpdatedAt()).isNull();
	}

	@Test
	@DisplayName("Reapuntar a otra organizacion reporta que cambio")
	void reapuntar_a_otra_organizacion_reporta_cambio() {
		AccountActiveContext puntero = nuevoPuntero();

		assertThat(puntero.pointTo(9L, SEDE)).isTrue();
		assertThat(puntero.getOrganizationId()).isEqualTo(9L);
		assertThat(puntero.getConsultorioId()).isEqualTo(SEDE);
	}

	@Test
	@DisplayName("Reapuntar a otra sede de la misma organizacion tambien reporta que cambio")
	void reapuntar_a_otra_sede_reporta_cambio() {
		AccountActiveContext puntero = nuevoPuntero();

		// El contexto es Organizacion + Consultorio: comparar solo la organizacion dejaria
		// pasar el cambio de sede sin auditar, que es un cambio de contexto real.
		assertThat(puntero.pointTo(ORG, 12L)).isTrue();
		assertThat(puntero.getConsultorioId()).isEqualTo(12L);
	}

	@Test
	@DisplayName("Reseleccionar el mismo contexto no reporta cambio")
	void reseleccionar_lo_mismo_no_reporta_cambio() {
		AccountActiveContext puntero = nuevoPuntero();

		// Sin esta distincion, cada refresco de la pantalla de seleccion agregaria una
		// entrada al historial de auditoria y lo volveria inservible.
		assertThat(puntero.pointTo(ORG, SEDE)).isFalse();
		assertThat(puntero.getOrganizationId()).isEqualTo(ORG);
		assertThat(puntero.getConsultorioId()).isEqualTo(SEDE);
	}

	@Test
	@DisplayName("La comparacion es por valor y no por identidad de referencia")
	void la_comparacion_es_por_valor() {
		AccountActiveContext puntero = nuevoPuntero();

		// Los ids llegan deserializados del request: son objetos Long distintos con el mismo
		// valor. Con == en lugar de equals, todo reapuntado por encima de la cache de Long
		// (127) se auditaria como cambio aunque no lo fuera.
		assertThat(puntero.pointTo(Long.valueOf(999L), Long.valueOf(888L))).isTrue();
		assertThat(puntero.pointTo(Long.valueOf(999L), Long.valueOf(888L))).isFalse();
	}

	@Test
	@DisplayName("El callback de JPA sella el instante de la ultima escritura")
	void el_callback_sella_el_instante() {
		AccountActiveContext puntero = nuevoPuntero();
		Instant antes = Instant.now();

		puntero.marcarInstante();

		// La fila es reemplazable y no tiene created_at: si updated_at quedara sin cargar, la
		// columna NOT NULL rechazaria el insert y la seleccion de contexto fallaria entera.
		assertThat(puntero.getUpdatedAt()).isNotNull();
		assertThat(puntero.getUpdatedAt()).isAfterOrEqualTo(antes);
	}

	@Test
	@DisplayName("El puntero hidratado por JPA no trae seleccion")
	void el_hidratado_por_jpa_no_trae_seleccion() {
		AccountActiveContext vacio = new AccountActiveContext();

		assertThat(vacio.getAccountId()).isNull();
		assertThat(vacio.getOrganizationId()).isNull();
		assertThat(vacio.getConsultorioId()).isNull();
	}
}
