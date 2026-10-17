package com.akine.diferidos;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.akine.organization.domain.exception.PermissionDeniedException;

import com.akine.scheduling.application.AgendaUnificadaService;
import com.akine.scheduling.application.OperatingActor;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R13 del threat model: las lecturas de {@code scheduling} amparadas por un acceso de soporte dejan
 * {@code SUPPORT_ACCESS_USED} en {@code audit_event}, contra MySQL.
 *
 * <p>Es el mismo escenario que {@code ActividadAislamientoIT} prueba para {@code activity}: el
 * {@code PLATFORM_ADMIN} tiene {@code turno:read} con alcance {@code SOPORTE}, y hasta R13 los
 * servicios de la agenda descartaban la decision del evaluador. Se entra por el servicio, con la
 * organizacion en el actor, porque hoy ninguna ruta de {@code scheduling} la lleva.
 *
 * <p>Lo que importa probar contra la base y no con un mock: que la fila <b>llegue</b>. Las
 * lecturas son {@code readOnly} —flush manual— y una que termina en 404 hace rollback; las dos
 * cosas se llevaban puesta la auditoria escrita en la transaccion del negocio.
 */
class SchedulingSoporteIT extends BaseEscenarioDiferido {

	@Autowired
	private RecepcionService recepcionService;

	@Autowired
	private AgendaUnificadaService agendaUnificadaService;

	@Test
	@DisplayName("R13 · el PLATFORM_ADMIN con soporte lee la agenda y deja SUPPORT_ACCESS_USED, tambien si termina en 404")
	void la_lectura_de_turnos_por_soporte_queda_auditada() {
		Sesion a = altaCompleta("r13-soporte");
		long soporte = altaCompleta("r13-soporte-admin").cuentaId();
		sembrarRolDePlataforma(soporte);
		OperatingActor actor = new OperatingActor(soporte, true, a.organizationId(), null);

		// Sin acceso de soporte vigente: 403 y ninguna fila.
		assertThatThrownBy(() -> recepcionService.delDia(actor, a.consultorioId(), LocalDate.now()))
				.isInstanceOf(PermissionDeniedException.class);
		assertThat(usos(soporte, a.organizationId())).as("sin soporte no hay uso que auditar").isEmpty();

		concederSoporte(a.organizationId(), soporte);

		assertThat(recepcionService.delDia(actor, a.consultorioId(), LocalDate.now()))
				.as("con soporte vigente la recepcion del dia se concede").isNotNull();
		assertThat(agendaUnificadaService.delDia(actor, a.consultorioId(), LocalDate.now()))
				.as("con soporte vigente la agenda unificada se concede").isNotNull();
		// Un turno que no existe: 404, y la fila tiene que quedar igual —el acceso se ejercio—.
		assertThatThrownBy(() -> recepcionService.ver(actor, a.consultorioId(), Long.MAX_VALUE))
				.isInstanceOf(TurnoNotAccessibleException.class);

		List<Map<String, Object>> usos = usos(soporte, a.organizationId());
		assertThat(usos).as("tres lecturas bajo soporte, tres filas").hasSize(3)
				.allSatisfy(fila -> {
					assertThat(String.valueOf(fila.get("details"))).contains("turno:read");
					assertThat(((Number) fila.get("consultorio_id")).longValue()).isEqualTo(a.consultorioId());
					assertThat(fila.get("entity_type")).isEqualTo("AGENDA");
				});

		// El ORG_ADMIN del centro lee lo mismo por su membership: no es soporte y no deja fila.
		OperatingActor propio = new OperatingActor(a.cuentaId(), false, a.organizationId(), a.consultorioId());
		recepcionService.delDia(propio, a.consultorioId(), LocalDate.now());
		assertThat(usos(a.cuentaId(), a.organizationId())).as("una lectura propia no es soporte").isEmpty();
	}

	private void concederSoporte(long organizationId, long cuentaId) {
		jdbc.update("""
				INSERT INTO support_access (organization_id, account_id, reason,
				                            granted_by_account_id, valid_from, valid_until,
				                            active, version, created_at, updated_at)
				VALUES (?, ?, 'Incidente sintetico de test de integracion', ?,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE),
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, cuentaId, cuentaId);
	}

	private List<Map<String, Object>> usos(long cuentaId, long organizationId) {
		return jdbc.queryForList("""
				SELECT consultorio_id, entity_type, details FROM audit_event
				 WHERE event_type = 'SUPPORT_ACCESS_USED' AND actor_account_id = ?
				   AND organization_id = ?
				""", cuentaId, organizationId);
	}
}
