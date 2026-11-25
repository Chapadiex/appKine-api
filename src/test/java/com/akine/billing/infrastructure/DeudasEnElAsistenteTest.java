package com.akine.billing.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.ObligacionService;
import com.akine.billing.domain.port.DeudorDelPaciente;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code deudas_persona} sin {@code persona_id}: la lista de quienes deben. */
class DeudasEnElAsistenteTest {

	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, 7L, 3L, "ADMINISTRATIVO");
	private static final ContextoDeConsulta CTX = new ContextoDeConsulta(LocalDate.of(2026, 11, 23),
			ZoneId.of("America/Argentina/Cordoba"), null);

	@Test
	void sin_persona_lista_quienes_deben_con_nombre_y_total() {
		ObligacionService obligaciones = mock(ObligacionService.class);
		when(obligaciones.deudoresDelPaciente(any(), eq(3L))).thenReturn(List.of(
				new DeudorDelPaciente(20L, new BigDecimal("30000.00"), 2, "ARS"),
				new DeudorDelPaciente(21L, new BigDecimal("5000.00"), 1, "ARS")));
		PacienteDirectory pacientes = mock(PacienteDirectory.class);
		when(pacientes.findAll(eq(7L), anyCollection())).thenReturn(Map.of(
				20L, new PacienteSnapshot(20L, 7L, "Quiroga", "Pedro", null, null, null, true, true, 1L, null),
				21L, new PacienteSnapshot(21L, 7L, "Díaz", "Ana", null, null, null, true, true, 2L, null)));
		DeudasEnElAsistente herramienta = new DeudasEnElAsistente(obligaciones, mock(CobroService.class), pacientes,
				mock(PermissionGuard.class));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of(), CTX);

		assertThat(r.filas()).extracting(f -> f.get("persona_id")).containsExactly(20L, 21L);
		assertThat(r.filas().getFirst()).containsEntry("nombre", "Quiroga, Pedro")
				.containsEntry("total_adeudado", new BigDecimal("30000.00"))
				.containsEntry("deudas_abiertas", 2L);
		assertThat(r.advertencias()).isEmpty();
	}
}
