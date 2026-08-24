package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica los invariantes de la sede.
 *
 * <p>El consultorio existe ya en 01.01 porque el onboarding compuesto lo crea en la misma
 * transaccion que la organizacion. Lo que se fija aca es el alcance tenant del vinculo y la
 * baja logica, que es la que libera cupo del limite MAX_CONSULTORIOS.
 */
class ConsultorioTest {

	private static final Long ORG = 5L;

	@Test
	@DisplayName("Un consultorio nuevo nace activo y atado a su organizacion")
	void nace_activo() {
		Consultorio consultorio = new Consultorio(ORG, "Sede Centro", "America/Argentina/Cordoba", null);

		assertThat(consultorio.isActive()).isTrue();
		assertThat(consultorio.getDeletedAt()).isNull();
		// El vinculo con la organizacion es el que hace que el nombre sea unico dentro del
		// tenant y no global: dos organizaciones pueden tener su "Sede Centro".
		assertThat(consultorio.getOrganizationId()).isEqualTo(ORG);
		assertThat(consultorio.getName()).isEqualTo("Sede Centro");
		assertThat(consultorio.getId()).isNull();
		assertThat(consultorio.getVersion()).isZero();
	}

	@Test
	@DisplayName("Renombrar la sede no la mueve de organizacion")
	void renombrar_no_cambia_de_organizacion() {
		Consultorio consultorio = new Consultorio(ORG, "Sede Centro", "America/Argentina/Cordoba", null);

		consultorio.rename("Sede Centro Ampliada");

		assertThat(consultorio.getName()).isEqualTo("Sede Centro Ampliada");
		// Mover una sede de tenant seria una fuga de datos entre organizaciones.
		assertThat(consultorio.getOrganizationId()).isEqualTo(ORG);
	}

	@Test
	@DisplayName("La baja es logica y conserva lo que ocurrio en la sede")
	void la_baja_es_logica() {
		Consultorio consultorio = new Consultorio(ORG, "Sede Centro", "America/Argentina/Cordoba", null);
		Instant momento = Instant.parse("2026-07-01T09:30:00Z");

		consultorio.deactivate(momento, "Cierre definitivo de la sucursal");

		// El conteo del limite MAX_CONSULTORIOS solo mira filas activas, asi que la baja
		// libera cupo; borrar la fila en cambio invalidaria la historia de esa sede.
		assertThat(consultorio.isActive()).isFalse();
		assertThat(consultorio.getDeletedAt()).isEqualTo(momento);
		assertThat(consultorio.getDeactivationReason()).isEqualTo("Cierre definitivo de la sucursal");
		assertThat(consultorio.isOperable()).isFalse();
		assertThat(consultorio.getName()).isEqualTo("Sede Centro");
	}

	@Test
	@DisplayName("El consultorio hidratado por JPA arranca activo")
	void el_hidratado_por_jpa_arranca_activo() {
		assertThat(new Consultorio().isActive()).isTrue();
	}

	@Test
	@DisplayName("La baja exige motivo declarado: sin el, la auditoria no responde nada")
	void la_baja_exige_motivo() {
		Consultorio consultorio = new Consultorio(ORG, "Sede Centro", "America/Argentina/Cordoba", null);

		assertThatThrownBy(() -> consultorio.deactivate(Instant.now(), "  "))
				.isInstanceOf(IllegalArgumentException.class);

		// Y no dejo la sede a medio dar de baja: el estado se toca DESPUES de la validacion.
		assertThat(consultorio.isOperable()).isTrue();
	}

	@Test
	@DisplayName("La edicion parcial deja lo que no se manda, y la cadena vacia borra el campo")
	void la_edicion_es_parcial() {
		Consultorio consultorio = new Consultorio(ORG, "Sede Centro", "America/Argentina/Cordoba", 45);
		consultorio.updateDatos(null, null, null, "Centro Kine SRL", null, null, null, null);

		// Semantica de PATCH: lo omitido no se toca. Un PUT convertiria cada campo que el
		// frontend todavia no conoce en un borrado accidental.
		consultorio.updateDatos(null, "America/Argentina/Ushuaia", null, "", null, null, null, null);

		assertThat(consultorio.getName()).isEqualTo("Sede Centro");
		assertThat(consultorio.getSlotMinutes()).isEqualTo(45);
		assertThat(consultorio.getTimezone()).isEqualTo("America/Argentina/Ushuaia");
		assertThat(consultorio.getLegalName()).isNull();
	}
}
