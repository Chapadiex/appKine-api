package com.akine.resource.domain;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La maquina de estados de una solicitud de alta de catalogo (RF-M06-005).
 *
 * <p>Tres estados y dos transiciones, las dos terminales. Lo que se prueba aca es que la
 * terminalidad y el motivo obligatorio son del <b>dominio</b> y no del servicio: dos
 * administradores de plataforma sobre la misma bandeja no pueden pisarse aunque el chequeo del
 * servicio fallara.
 *
 * <p>Lo que NO se prueba aca es la idempotencia del pedido duplicado: eso lo sostiene un unique
 * de la base y se ejerce en {@code CatalogosIT}, porque entre un SELECT y un INSERT caben dos
 * requests y un test en memoria no lo demostraria.
 */
class CatalogoSolicitudTest {

	private static final Instant AHORA = Instant.parse("2026-06-01T00:00:00Z");

	@Test
	@DisplayName("Una solicitud nace PENDIENTE, sin nada de la resolucion cargado")
	void nace_pendiente() {
		CatalogoSolicitud solicitud = solicitud();

		assertThat(solicitud.estaPendiente()).isTrue();
		assertThat(solicitud.getEstado()).isEqualTo(SolicitudEstado.PENDIENTE);
		assertThat(solicitud.getResueltaAt()).isNull();
		assertThat(solicitud.getResueltaPorAccountId()).isNull();
		assertThat(solicitud.getResolucionNota()).isNull();
		assertThat(solicitud.getConceptoId()).isNull();
	}

	@Test
	@DisplayName("Aprobar deja quien, cuando, por que y el concepto creado")
	void aprobar_registra_la_decision_completa() {
		CatalogoSolicitud solicitud = solicitud();

		solicitud.resolver(SolicitudEstado.APROBADA, 42L, "Se suma al catalogo comun", 7L, AHORA);

		assertThat(solicitud.getEstado()).isEqualTo(SolicitudEstado.APROBADA);
		assertThat(solicitud.estaPendiente()).isFalse();
		assertThat(solicitud.getResueltaPorAccountId()).isEqualTo(42L);
		assertThat(solicitud.getResueltaAt()).isEqualTo(AHORA);
		assertThat(solicitud.getResolucionNota()).isEqualTo("Se suma al catalogo comun");
		assertThat(solicitud.getConceptoId()).isEqualTo(7L);
	}

	@Test
	@DisplayName("La resolucion es terminal: no se reabre ni se cambia de opinion")
	void la_resolucion_es_terminal() {
		CatalogoSolicitud solicitud = solicitud();
		solicitud.resolver(SolicitudEstado.RECHAZADA, 42L, "No corresponde al catalogo", null, AHORA);

		assertThatThrownBy(() -> solicitud.resolver(
				SolicitudEstado.APROBADA, 43L, "Me arrepenti", null, AHORA))
				.isInstanceOf(IllegalStateException.class);

		assertThat(solicitud.getEstado())
				.as("y la decision original queda intacta: aprobar sobre un rechazo ajeno "
						+ "borraria de que se decidio la primera vez")
				.isEqualTo(SolicitudEstado.RECHAZADA);
	}

	@Test
	@DisplayName("PENDIENTE no es un desenlace, y sin motivo no hay resolucion")
	void resolver_exige_un_desenlace_y_un_motivo() {
		assertThatThrownBy(() -> solicitud()
				.resolver(SolicitudEstado.PENDIENTE, 42L, "Nota", null, AHORA))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> solicitud()
				.resolver(null, 42L, "Nota", null, AHORA))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> solicitud()
				.resolver(SolicitudEstado.APROBADA, 42L, "   ", null, AHORA))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Sin tenant, sin tipo o sin solicitante no hay solicitud")
	void las_referencias_son_obligatorias() {
		assertThatThrownBy(() -> new CatalogoSolicitud(
				null, 2L, CatalogoTipo.ESPECIALIDAD, "N", null, "Justificacion", 3L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CatalogoSolicitud(
				1L, 2L, null, "N", null, "Justificacion", 3L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CatalogoSolicitud(
				1L, 2L, CatalogoTipo.ESPECIALIDAD, "N", null, "Justificacion", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("La justificacion es obligatoria: sin ella la plataforma no puede decidir")
	void la_justificacion_es_obligatoria() {
		assertThatThrownBy(() -> new CatalogoSolicitud(
				1L, 2L, CatalogoTipo.ESPECIALIDAD, "Terapia ocupacional", null, "  ", 3L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("justificacion");
		assertThatThrownBy(() -> new CatalogoSolicitud(
				1L, 2L, CatalogoTipo.ESPECIALIDAD, "   ", null, "Justificacion", 3L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("El codigo propuesto es opcional y la cadena vacia equivale a no proponerlo")
	void el_codigo_propuesto_es_opcional() {
		assertThat(new CatalogoSolicitud(
				1L, null, CatalogoTipo.PRACTICA, "  Terapia  ", "  ", "  Porque si  ", 3L))
				.satisfies(solicitud -> {
					assertThat(solicitud.getCodigoPropuesto()).isNull();
					assertThat(solicitud.getConsultorioId()).isNull();
					assertThat(solicitud.getNombrePropuesto()).isEqualTo("Terapia");
					assertThat(solicitud.getJustificacion()).isEqualTo("Porque si");
				});
	}

	private static CatalogoSolicitud solicitud() {
		return new CatalogoSolicitud(
				1L, 2L, CatalogoTipo.ESPECIALIDAD, "Terapia ocupacional", "TO",
				"Tres profesionales del centro la ejercen", 3L);
	}
}
