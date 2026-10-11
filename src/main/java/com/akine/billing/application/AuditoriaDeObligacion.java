package com.akine.billing.application;

import com.akine.billing.domain.Obligacion;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Las filas de {@code audit_event} de la deuda (M18): devengarla y anularla.
 *
 * <p>Publica porque el devengo no nace en un servicio de esta capa sino en
 * {@code billing.infrastructure.ObligacionDevengador}, que reacciona al cierre de la sesion, y el
 * catalogo {@link AuditEvents} es del paquete. Misma forma que {@link ImputacionDePrepago}.
 *
 * <p>Corre en la transaccion de quien la llama (regla de 01.01): si el cierre o la anulacion no
 * commitean, la fila tampoco. Sin PHI: ni la persona ni el nombre de la prestacion, solo montos,
 * concepto y la sesion de origen, igual que los eventos de cobro.
 */
@Component
public class AuditoriaDeObligacion {

	private final AuditTrail auditTrail;

	public AuditoriaDeObligacion(AuditTrail auditTrail) {
		this.auditTrail = auditTrail;
	}

	/** Una deuda recien devengada. {@code actorCuentaId} es quien cerro la sesion. */
	public void devengada(Obligacion obligacion, Long actorCuentaId, Instant ahora) {
		Map<String, String> detalles = new LinkedHashMap<>();
		if (obligacion.getSesionId() != null) {
			detalles.put("sesionId", String.valueOf(obligacion.getSesionId()));
		}
		detalles.put("responsable", obligacion.getResponsable().name());
		if (obligacion.getConcepto() != null) {
			detalles.put("concepto", obligacion.getConcepto().name());
		}
		if (obligacion.getFinanciadorId() != null) {
			detalles.put("financiadorId", String.valueOf(obligacion.getFinanciadorId()));
		}
		detalles.put("importe", obligacion.getImporteOriginal().toPlainString());
		detalles.put("moneda", obligacion.getMoneda());
		registrar(obligacion, actorCuentaId, AuditEvents.OBLIGACION_DEVENGADA,
				null, obligacion.getEstado().name(), detalles, null, ahora);
	}

	/** Una deuda anulada. {@code estadoAnterior} es el leido antes de anular. */
	public void anulada(
			Obligacion obligacion, String estadoAnterior, long actorCuentaId, String motivo,
			Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("importe", obligacion.getImporteOriginal().toPlainString());
		detalles.put("saldo", obligacion.getSaldo().toPlainString());
		detalles.put("moneda", obligacion.getMoneda());
		registrar(obligacion, actorCuentaId, AuditEvents.OBLIGACION_ANULADA,
				estadoAnterior, obligacion.getEstado().name(), detalles, motivo, ahora);
	}

	private void registrar(
			Obligacion obligacion, Long actorCuentaId, String eventType, String anterior,
			String nuevo, Map<String, String> detalles, String motivo, Instant ahora) {

		auditTrail.record(new AuditEntry(
				obligacion.getOrganizationId(),
				obligacion.getConsultorioId(),
				actorCuentaId,
				eventType,
				AuditEvents.ENTITY_OBLIGACION,
				obligacion.getId(),
				anterior,
				nuevo,
				detalles,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}
}
