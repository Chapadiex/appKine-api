package com.akine.clinical.domain;

import com.akine.clinical.domain.exception.TransicionDePlanInvalidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las reglas que el Plan de Tratamiento hace cumplir en la entidad y no en el servicio (M11).
 *
 * <p>Que vivan aca significa que ningun camino de escritura —ni uno futuro que no pase por el
 * controller— puede dejar un plan sin objetivos, una version posterior sin motivo o un item con
 * cantidades fuera del rango que admiten los CHECK de {@code V49}.
 */
@DisplayName("Plan de Tratamiento: invariantes de la entidad")
class PlanDeTratamientoInvariantesTest {

	private static final Instant AHORA = Instant.parse("2026-10-14T12:00:00Z");

	// =================================================================================
	// Item
	// =================================================================================

	@Test
	@DisplayName("la cantidad planificada va de 1 a 9999 y la autorizada de 0 a 9999")
	void rangos_del_item() {
		assertThatThrownBy(() -> item("Kinesiologia", 0, null))
				.as("planificar cero sesiones no es un plan")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> item("Kinesiologia", PlanItem.CANTIDAD_MAXIMA + 1, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> item("Kinesiologia", 10, -1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> item("Kinesiologia", 10, PlanItem.CANTIDAD_MAXIMA + 1))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(item("Kinesiologia", PlanItem.CANTIDAD_MAXIMA, 0).getCantidadAutorizada())
				.as("autorizar cero es legitimo: el financiador puede no cubrir nada")
				.isZero();
	}

	@Test
	@DisplayName("el item congela un nombre de oferta no vacio, recortado al maximo de la columna")
	void nombre_congelado() {
		assertThatThrownBy(() -> item("   ", 10, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> item(null, 10, null)).isInstanceOf(IllegalArgumentException.class);

		String largo = "K".repeat(PlanItem.NOMBRE_MAXIMO + 15);
		assertThat(item("  " + largo, 10, null).getOfertaNombre())
				.hasSize(PlanItem.NOMBRE_MAXIMO);
		assertThatThrownBy(() -> new PlanItem(1L, 2L, 3L, 4L, "Kinesiologia", null, 10, null))
				.as("sin servicio no hay snapshot de la oferta")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("vincular una autorizacion respeta el mismo rango, y sin cantidad deja la cantidad en nulo")
	void vincular_autorizacion() {
		PlanItem item = item("Kinesiologia", 10, 8);

		assertThatThrownBy(() -> item.vincularAutorizacion(6100L, -3))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> item.vincularAutorizacion(6100L, PlanItem.CANTIDAD_MAXIMA + 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(item.getAutorizacionId()).as("el rechazo no deja el item a medio atar").isNull();

		item.vincularAutorizacion(6100L, null);
		assertThat(item.getAutorizacionId()).isEqualTo(6100L);
		assertThat(item.getOrigenAutorizacion()).isEqualTo(OrigenCantidadAutorizada.AUTORIZACION);
		assertThat(item.getCantidadAutorizada())
				.as("una autorizacion sin tope reemplaza la cantidad declarada, no la conserva")
				.isNull();
	}

	// =================================================================================
	// Version
	// =================================================================================

	@Test
	@DisplayName("una version posterior a la 1 exige motivo; la 1 lo descarta aunque venga")
	void motivo_de_la_version() {
		assertThatThrownBy(() -> version(2, "Objetivos", "  "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> version(0, "Objetivos", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> version(2, "Objetivos", "m".repeat(PlanTratamientoVersion.MOTIVO_MAXIMO + 1)))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(version(1, "Objetivos", "no aplica").getMotivoModificacion()).isNull();
		assertThat(version(3, "Objetivos", "  Se agrega fisio ").getMotivoModificacion())
				.isEqualTo("Se agrega fisio");
	}

	@Test
	@DisplayName("el contenido exige objetivos y frecuencia y duracion en rango")
	void contenido_de_la_version() {
		assertThatThrownBy(() -> version(1, " ", null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> version(1, "o".repeat(PlanTratamientoVersion.TEXTO_MAXIMO + 1), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PlanTratamientoVersion(1L, 2L, 1, "Objetivos", null,
				PlanTratamientoVersion.FRECUENCIA_MAXIMA + 1, null, null, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PlanTratamientoVersion(1L, 2L, 1, "Objetivos", null,
				null, 0, null, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);

		PlanTratamientoVersion sinFrecuencia = new PlanTratamientoVersion(1L, 2L, 1, "Objetivos",
				"  ", null, null, null, AHORA, 9L);
		assertThat(sinFrecuencia.getFrecuenciaSemanal()).isNull();
		assertThat(sinFrecuencia.getIndicaciones()).as("en blanco es ausencia").isNull();
	}

	@Test
	@DisplayName("solo la version 1 se edita en el lugar: las demas son historia")
	void editar_en_borrador() {
		PlanTratamientoVersion segunda = version(2, "Objetivos", "Cambio de conducta");

		assertThatThrownBy(() -> segunda.editarEnBorrador("Otros", null, 2, 4))
				.isInstanceOf(IllegalStateException.class);
		assertThat(segunda.getObjetivos()).isEqualTo("Objetivos");
	}

	// =================================================================================
	// Cabecera
	// =================================================================================

	@Test
	@DisplayName("el correlativo del plan es positivo")
	void correlativo_positivo() {
		assertThatThrownBy(() -> new PlanTratamiento(1L, 2L, 0, AHORA, 9L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("las transiciones fuera de la maquina son 409, y el motivo se valida antes que el estado")
	void transiciones_invalidas() {
		PlanTratamiento borrador = new PlanTratamiento(1L, 2L, 1, AHORA, 9L);

		assertThatThrownBy(() -> borrador.suspender("Viaje", AHORA))
				.as("no se suspende lo que nunca se activo")
				.isInstanceOf(TransicionDePlanInvalidaException.class);
		assertThatThrownBy(() -> borrador.suspender(" ", AHORA))
				.as("sin motivo es 400 aunque el estado tampoco lo admita")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> borrador.suspender("x".repeat(PlanTratamiento.MOTIVO_MAXIMO + 1), AHORA))
				.isInstanceOf(IllegalArgumentException.class);

		borrador.finalizar("No se inicio", AHORA, 9L);
		assertThatThrownBy(() -> borrador.activar(AHORA, 9L))
				.as("un FINALIZADO no se reabre")
				.isInstanceOf(TransicionDePlanInvalidaException.class);
		assertThat(borrador.perteneceACaso(2L)).isTrue();
		assertThat(borrador.perteneceACaso(3L)).isFalse();
	}

	@Test
	@DisplayName("suspender dos veces conserva el motivo original; reanudar restituye la activacion perdida")
	void suspender_y_reanudar() {
		PlanTratamiento plan = new PlanTratamiento(1L, 2L, 1, AHORA, 9L);
		plan.activar(AHORA, 9L);

		assertThat(plan.suspender("Viaje del paciente", AHORA)).isTrue();
		assertThat(plan.suspender("Otro motivo", AHORA)).isFalse();
		assertThat(plan.getMotivoSuspension()).isEqualTo("Viaje del paciente");

		// Una fila escrita por fuera de esta clase: SUSPENDIDO sin instante de activacion.
		ReflectionTestUtils.setField(plan, "activadoEn", null);
		Instant despues = AHORA.plusSeconds(3600);
		assertThat(plan.reanudar(despues)).isTrue();
		assertThat(plan.getActivadoEn())
				.as("el CHECK de activacion no admite un ACTIVO sin instante")
				.isEqualTo(despues);
		assertThat(plan.getSuspendidoEn()).isNull();
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static PlanItem item(String nombre, int planificada, Integer autorizada) {
		return new PlanItem(1L, 2L, 3L, 4L, nombre, 5L, planificada, autorizada);
	}

	private static PlanTratamientoVersion version(int numero, String objetivos, String motivo) {
		return new PlanTratamientoVersion(1L, 2L, numero, objetivos, null, 3, 8, motivo, AHORA, 9L);
	}
}
