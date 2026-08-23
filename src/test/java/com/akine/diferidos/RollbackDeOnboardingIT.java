package com.akine.diferidos;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.akine.identity.domain.port.NotificationOutboxPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Escenario 5 de {@code docs/tests-diferidos.md} (ADR-0008, CA-001-04, test 7).
 *
 * <h2>Donde se inyecta el fallo y por que ahi</h2>
 *
 * <p>El alta self-service escribe, en este orden y en UNA transaccion: {@code cuenta},
 * {@code organization}, {@code consultorio}, {@code subscription},
 * {@code subscription_transition}, {@code membership}, {@code organization_onboarding}, y
 * recien despues emite el token de activacion y <b>encola el correo</b>.
 *
 * <p>El fallo se inyecta justo en ese ultimo paso —{@code NotificationOutboxPort.encolar}—
 * porque es el punto donde <b>las cuatro tablas del criterio ya estan escritas</b>. Un fallo
 * mas temprano probaria mucho menos: haria rollback de lo poco que hubiera. Aca el rollback
 * tiene que deshacer el alta entera, que es lo que ADR-0008 promete y lo que nadie habia
 * verificado nunca de punta a punta.
 *
 * <p>Es la unica clase de este paquete que usa un doble: no se sustituye nada del camino de
 * autenticacion, se sustituye el adaptador de salida hacia {@code notification} para provocar
 * el fallo. La transaccion, la base y el endpoint son los reales.
 */
class RollbackDeOnboardingIT extends BaseEscenarioDiferido {

	@MockitoBean
	private NotificationOutboxPort notificationOutbox;

	@Test
	@DisplayName("5. Fallo a mitad del alta compuesta: rollback total, cero filas en las cuatro tablas")
	void el_fallo_parcial_revierte_el_alta_completa() {
		doThrow(new IllegalStateException("fallo inyectado por el escenario 5"))
				.when(notificationOutbox).encolar(any());

		String email = "rollback-" + UUID.randomUUID() + "@ejemplo.test";
		String slug = "rollback-" + UUID.randomUUID().toString().substring(0, 8);

		long cuentasAntes = contarFilas("cuenta");
		long organizacionesAntes = contarFilas("organization");
		long consultoriosAntes = contarFilas("consultorio");
		long membershipsAntes = contarFilas("membership");
		long suscripcionesAntes = contarFilas("subscription");
		long onboardingsAntes = contarFilas("organization_onboarding");

		Respuesta alta = registrar(UUID.randomUUID().toString(), email, "Rollback", slug, null);

		assertThat(alta.status())
				.as("el alta no pudo completarse, asi que no puede acusarse recibo: %s", alta.body())
				.isNotEqualTo(202);
		assertProblemaLimpio(alta);

		assertThat(contarFilas("cuenta"))
				.as("cuenta: el rollback tiene que borrar la fila ya flusheada")
				.isEqualTo(cuentasAntes);
		assertThat(contarFilas("organization"))
				.as("organization")
				.isEqualTo(organizacionesAntes);
		assertThat(contarFilas("consultorio"))
				.as("consultorio")
				.isEqualTo(consultoriosAntes);
		assertThat(contarFilas("membership"))
				.as("membership")
				.isEqualTo(membershipsAntes);

		// Las dos tablas restantes del alta compuesta, por completitud del invariante.
		assertThat(contarFilas("subscription")).isEqualTo(suscripcionesAntes);
		assertThat(contarFilas("organization_onboarding")).isEqualTo(onboardingsAntes);

		// Y nada quedo apuntando a este intento.
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM cuenta WHERE email_normalizado = ?",
				Long.class, normalizar(email)))
				.as("ninguna cuenta huerfana con ese email")
				.isZero();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM organization WHERE slug = ?", Long.class, slug))
				.as("ningun tenant huerfano con ese slug")
				.isZero();
	}
}
