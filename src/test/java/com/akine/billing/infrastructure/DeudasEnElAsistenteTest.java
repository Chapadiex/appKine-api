package com.akine.billing.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.CobroView;
import com.akine.billing.application.ObligacionService;
import com.akine.billing.application.ObligacionView;
import com.akine.billing.domain.port.DeudorDelPaciente;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

	@Test
	void con_persona_los_totales_van_por_moneda_y_no_se_suman() {
		ObligacionService obligaciones = mock(ObligacionService.class);
		when(obligaciones.deLaPersona(any(), eq(3L), eq(20L))).thenReturn(List.of(
				obligacion(1L, "1000.00", "ARS"),
				obligacion(2L, "500.00", "ARS"),
				obligacion(3L, "40.00", "USD")));
		CobroService cobros = mock(CobroService.class);
		when(cobros.deLaPersona(any(), eq(3L), eq(20L))).thenReturn(List.of(
				cobro(9L, "10.00", "USD")));
		PacienteDirectory pacientes = mock(PacienteDirectory.class);
		when(pacientes.find(7L, 20L)).thenReturn(Optional.of(
				new PacienteSnapshot(20L, 7L, "Quiroga", "Pedro", null, null, null, true, true, 1L, null)));
		DeudasEnElAsistente herramienta = new DeudasEnElAsistente(obligaciones, cobros, pacientes,
				mock(PermissionGuard.class));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("persona_id", 20), CTX);

		List<Map<String, Object>> totales = r.filas().stream().filter(f -> "TOTAL".equals(f.get("fila"))).toList();
		assertThat(totales).hasSize(2);
		assertThat(totales.get(0)).containsEntry("moneda", "ARS")
				.containsEntry("deuda_abierta", new BigDecimal("1500.00"))
				.containsEntry("deudas_abiertas", 2)
				.containsEntry("saldo_a_favor", BigDecimal.ZERO);
		assertThat(totales.get(1)).containsEntry("moneda", "USD")
				.containsEntry("deuda_abierta", new BigDecimal("40.00"))
				.containsEntry("saldo_a_favor", new BigDecimal("10.00"))
				.containsEntry("neto_a_pagar", new BigDecimal("30.00"));
		assertThat(r.advertencias()).anyMatch(a -> a.contains("más de una moneda"));
	}

	private static ObligacionView obligacion(long id, String saldo, String moneda) {
		BigDecimal importe = new BigDecimal(saldo);
		return new ObligacionView(id, 3L, null, 20L, "PACIENTE", null, importe, importe, moneda, "PENDIENTE",
				1L, "Kinesiologia", importe, Instant.parse("2026-11-01T12:00:00Z").plusSeconds(id), null, null, 0L,
				null, null, false, null, null, null, null, null);
	}

	private static CobroView cobro(long id, String saldoAFavor, String moneda) {
		BigDecimal saldo = new BigDecimal(saldoAFavor);
		return new CobroView(id, 3L, 20L, saldo, moneda, 1, Instant.parse("2026-11-02T12:00:00Z"), List.of(),
				List.of(), 0L, saldo, CobroView.VIGENTE, null, null, null);
	}
}
