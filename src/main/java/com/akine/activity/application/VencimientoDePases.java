package com.akine.activity.application;

import com.akine.activity.domain.EstadoPase;
import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.port.ActivityRepositoryPorts.CandidatoAVencer;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El vencimiento programado de pases y abonos (AKINE-08.07, RF-M29-006, CA-M29-006-06).
 *
 * <h2>Por que se persiste, si "la efectiva se calcula al leer"</h2>
 *
 * <p>La lectura ya lo calcula —{@code PaseView.vigente} es falso apenas termina la vigencia, y el
 * consumo nunca toma un pase fuera de fecha—, y eso no cambia. Lo que la lectura no puede dar es lo
 * que RF-M29-006 pide literalmente: <b>que los creditos remanentes queden explicados por un
 * movimiento {@code VENCIMIENTO}</b> y que el saldo mostrado coincida con la reconstruccion por
 * movimientos (RF-M29-009). Un saldo de 5 en un pase vencido es un ledger que no explica adonde
 * fueron esos 5. Por eso el job escribe {@code VENCIDO}, pone el saldo en cero y deja el movimiento.
 *
 * <h2>Correctitud</h2>
 *
 * <ul>
 *   <li><b>"Hoy" es el de la sede</b> de cada pase: un pase que vence el 31 vence despues de las 24
 *       de Cordoba, no de las 21.</li>
 *   <li><b>Cada pase en su transaccion</b>, corta y en {@code READ_COMMITTED}: un pase que falla no
 *       revierte los demas, y no hay una transaccion larga sosteniendo locks sobre todo el lote. El
 *       metodo que recorre el lote no es transaccional; no hay {@code REQUIRES_NEW} anidado.</li>
 *   <li><b>Dos instancias del job no vencen dos veces</b>: el pase se bloquea, el {@code UPDATE}
 *       exige {@code ACTIVO} y fecha pasada en su {@code WHERE}, y el movimiento choca contra
 *       {@code uk_movimiento_origen} sobre la version que vencio.</li>
 *   <li>Solo {@code ACTIVO}: un {@code AGOTADO} no tiene nada que descartar. Si una correccion
 *       tardia le devuelve un credito vuelve a {@code ACTIVO}, y el proximo tick lo vence con su
 *       movimiento.</li>
 * </ul>
 *
 * <p><b>Un credito no es plata</b> (RN-M29-006): vencer no toca la obligacion ni la caja.
 */
@Component
public class VencimientoDePases {

	private static final Logger log = LoggerFactory.getLogger(VencimientoDePases.class);

	private final PaseServicioRepositoryPort pases;
	private final MovimientoPaseRepositoryPort movimientos;
	private final ConsultorioDirectory consultorios;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccion;
	private final Clock clock;

	@Autowired
	public VencimientoDePases(
			PaseServicioRepositoryPort pases,
			MovimientoPaseRepositoryPort movimientos,
			ConsultorioDirectory consultorios,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {

		this(pases, movimientos, consultorios, auditTrail, transactionManager, Clock.systemUTC());
	}

	VencimientoDePases(
			PaseServicioRepositoryPort pases,
			MovimientoPaseRepositoryPort movimientos,
			ConsultorioDirectory consultorios,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager,
			Clock clock) {

		this.pases = pases;
		this.movimientos = movimientos;
		this.consultorios = consultorios;
		this.auditTrail = auditTrail;
		this.transaccion = new TransactionTemplate(transactionManager);
		this.transaccion.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.clock = clock;
	}

	/**
	 * Vence hasta {@code maximo} pases cuya vigencia termino. <b>No es transaccional</b>: cada pase
	 * abre y cierra la suya.
	 *
	 * @return cuantos paso a {@code VENCIDO} esta llamada
	 */
	public int vencerLosVencidos(int maximo) {
		Instant ahora = clock.instant();
		// El dia mas adelantado del planeta es, como mucho, el de UTC mas uno: todo pase vencido en su
		// sede tiene vigencia_hasta anterior a ese limite. Los que todavia no vencieron en su zona se
		// descartan abajo, uno por uno.
		LocalDate limite = LocalDate.ofInstant(ahora, ZoneOffset.UTC).plusDays(1);
		List<CandidatoAVencer> candidatos = pases.candidatosAVencer(limite, maximo);
		int vencidos = 0;
		for (CandidatoAVencer candidato : candidatos) {
			try {
				if (vencerUno(candidato, ahora)) {
					vencidos++;
				}
			} catch (RuntimeException e) {
				// Un pase que falla no detiene el lote: queda ACTIVO y lo reintenta el proximo tick.
				log.error("No se pudo vencer el pase {}", candidato.paseId(), e);
			}
		}
		if (vencidos > 0) {
			log.info("Pases vencidos: {} de {} candidatos", vencidos, candidatos.size());
		}
		return vencidos;
	}

	private boolean vencerUno(CandidatoAVencer candidato, Instant ahora) {
		LocalDate hoy = LocalDate.ofInstant(ahora,
				ZonaDeSede.de(consultorios, candidato.organizationId(), candidato.consultorioId()));
		if (!candidato.vigenciaHasta().isBefore(hoy)) {
			return false;
		}
		Boolean vencio = transaccion.execute(estado -> vencerEnTransaccion(candidato, hoy, ahora));
		return Boolean.TRUE.equals(vencio);
	}

	private boolean vencerEnTransaccion(CandidatoAVencer candidato, LocalDate hoy, Instant ahora) {
		long organizationId = candidato.organizationId();
		long paseId = candidato.paseId();
		PaseServicio pase = pases.lockByIdInScope(organizationId, candidato.consultorioId(), paseId)
				.orElse(null);
		if (pase == null || pase.getEstado() != EstadoPase.ACTIVO || !pase.getVigenciaHasta().isBefore(hoy)) {
			// Otra instancia lo vencio, o un ajuste/anulacion lo cambio entre la lista y el lock.
			return false;
		}
		int descartados = pase.getCreditosDisponibles();
		if (pases.vencer(organizationId, paseId, hoy) != 1) {
			return false;
		}
		if (descartados > 0) {
			movimientos.registrar(MovimientoPase.vencimiento(
					organizationId, paseId, descartados, pase.getVersion(), ahora));
		}
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("creditosDescartados", String.valueOf(descartados));
		detalles.put("vigenciaHasta", pase.getVigenciaHasta().toString());
		auditTrail.record(new AuditEntry(
				organizationId, candidato.consultorioId(), null, "PASE_VENCIDO", "PASE_SERVICIO", paseId,
				EstadoPase.ACTIVO.name(), EstadoPase.VENCIDO.name(), detalles, null, null, ahora));
		return true;
	}
}
