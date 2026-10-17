package com.akine.activity.application;

import com.akine.activity.domain.EstadoPase;
import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.ProductoServicio;
import com.akine.activity.domain.TipoProducto;
import com.akine.activity.domain.port.ActivityRepositoryPorts.CandidatoARenovar;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ProductoServicioRepositoryPort;
import com.akine.billing.spi.DevengoDeVentas;
import com.akine.billing.spi.DevengoDeVentas.DeudaDeVenta;
import com.akine.billing.spi.DevengoDeVentas.VentaDePase;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
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
import java.util.Optional;

/**
 * La renovacion automatica de abonos (AKINE-08.08; plan: "renovacion idempotente", "un periodo
 * genera como maximo una obligacion"; RF-M29-007, RF-M18-010).
 *
 * <h2>Que es renovar</h2>
 *
 * <p>Vender el periodo siguiente, exactamente como lo venderia el mostrador: una fila nueva de
 * {@code pase_servicio} con el snapshot del producto de hoy, su movimiento {@code COMPRA} y <b>una</b>
 * obligacion {@code VENTA_ABONO} por {@code billing.spi.DevengoDeVentas}. No cobra (DP-06). Solo se
 * renueva un abono cuya renovacion alguien <b>pidio</b> (opt-in, V103), y se vende a nombre de quien
 * la pidio: el job no es una persona y la deuda tiene que tener a alguien que la haya autorizado.
 *
 * <h2>Se activa al venderse, como hoy (U-40 b, abierta)</h2>
 *
 * <p>"Activa el abono segun politica de pago" (RF-M29-007 paso 7) no tiene politica declarada, y
 * #135 eligio activar al vender. La renovacion sigue esa misma regla y <b>no mira si el periodo
 * anterior se pago</b>: decidir que un abono impago no se renueva seria resolver U-40 por la puerta
 * de atras. Si U-40 se decide "al cobrarse", cambia en los dos lugares a la vez.
 *
 * <h2>Correctitud</h2>
 *
 * <ul>
 *   <li><b>"Hoy" es el de la sede.</b> Se renueva cuando faltan {@code anticipacionDias} o menos
 *       para el fin del periodo, para que la deuda del siguiente exista antes de que empiece.</li>
 *   <li><b>Cada abono en su transaccion</b>, corta y en {@code READ_COMMITTED}; el lote no es
 *       transaccional (sin {@code REQUIRES_NEW} anidado). Un abono que falla no frena a los demas.</li>
 *   <li><b>Orden de locks {@code producto_servicio -> pase_servicio}</b>, el mismo de la venta: el
 *       producto bloqueado serializa contra una venta manual del mismo producto, que es lo que hace
 *       valer el control de superposicion.</li>
 *   <li><b>Dos instancias, o un reintento, no renuevan dos veces.</b> Tres capas: el abono se
 *       bloquea y se relee (lectura actual); la renovacion se apaga en el periodo viejo en la misma
 *       transaccion que crea el nuevo, asi que la segunda pasada no lo encuentra; y
 *       {@code uk_pase_servicio_renovacion} impide un segundo periodo que renueve al mismo.</li>
 *   <li><b>Lo que no se puede renovar se apaga con su motivo</b> —producto de baja o fuera de venta,
 *       persona de baja, el periodo ya ocupado por una venta manual, el periodo siguiente ya
 *       terminado—, en vez de reintentarse para siempre. Queda en
 *       {@code renovacion_omitida_motivo} y en la auditoria.</li>
 * </ul>
 */
@Component
public class RenovacionDeAbonos {

	private static final Logger log = LoggerFactory.getLogger(RenovacionDeAbonos.class);

	private static final String ENTIDAD = "PASE_SERVICIO";

	private final PaseServicioRepositoryPort pases;
	private final ProductoServicioRepositoryPort productos;
	private final MovimientoPaseRepositoryPort movimientos;
	private final DevengoDeVentas devengo;
	private final PacienteDirectory personas;
	private final ConsultorioDirectory consultorios;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccion;
	private final Clock clock;

	@Autowired
	public RenovacionDeAbonos(
			PaseServicioRepositoryPort pases,
			ProductoServicioRepositoryPort productos,
			MovimientoPaseRepositoryPort movimientos,
			DevengoDeVentas devengo,
			PacienteDirectory personas,
			ConsultorioDirectory consultorios,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {

		this(pases, productos, movimientos, devengo, personas, consultorios, auditTrail, transactionManager,
				Clock.systemUTC());
	}

	RenovacionDeAbonos(
			PaseServicioRepositoryPort pases,
			ProductoServicioRepositoryPort productos,
			MovimientoPaseRepositoryPort movimientos,
			DevengoDeVentas devengo,
			PacienteDirectory personas,
			ConsultorioDirectory consultorios,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager,
			Clock clock) {

		this.pases = pases;
		this.productos = productos;
		this.movimientos = movimientos;
		this.devengo = devengo;
		this.personas = personas;
		this.consultorios = consultorios;
		this.auditTrail = auditTrail;
		this.transaccion = new TransactionTemplate(transactionManager);
		this.transaccion.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.clock = clock;
	}

	/** Lo que paso con un abono candidato. */
	public enum Resultado {
		/** Se vendio el periodo siguiente. */
		RENOVADO,
		/** No se podia renovar: se apago la renovacion con su motivo. */
		OMITIDO,
		/** Nada que hacer: todavia no es tiempo, otra instancia ya lo resolvio o se apago. */
		SIN_CAMBIOS
	}

	/**
	 * Renueva hasta {@code maximo} abonos cuyo periodo termina dentro de {@code anticipacionDias}.
	 * <b>No es transaccional</b>: cada abono abre y cierra la suya.
	 *
	 * @return cuantos renovo esta llamada
	 */
	public int renovarLosQueTerminan(int anticipacionDias, int maximo) {
		Instant ahora = clock.instant();
		// El dia mas adelantado del planeta es, como mucho, el de UTC mas uno: todo abono que en su
		// sede ya entro en la ventana tiene vigencia_hasta hasta ese limite. Los que todavia no, se
		// descartan abajo uno por uno.
		LocalDate limite = LocalDate.ofInstant(ahora, ZoneOffset.UTC).plusDays(1L + anticipacionDias);
		List<CandidatoARenovar> candidatos = pases.candidatosARenovar(limite, maximo);
		int renovados = 0;
		for (CandidatoARenovar candidato : candidatos) {
			try {
				if (renovarUno(candidato, anticipacionDias, ahora) == Resultado.RENOVADO) {
					renovados++;
				}
			} catch (RuntimeException e) {
				// Un abono que falla no detiene el lote: sigue con la renovacion prendida y lo
				// reintenta el proximo tick.
				log.error("No se pudo renovar el abono {}", candidato.paseId(), e);
			}
		}
		if (renovados > 0) {
			log.info("Abonos renovados: {} de {} candidatos", renovados, candidatos.size());
		}
		return renovados;
	}

	Resultado renovarUno(CandidatoARenovar candidato, int anticipacionDias, Instant ahora) {
		LocalDate hoy = LocalDate.ofInstant(ahora,
				ZonaDeSede.de(consultorios, candidato.organizationId(), candidato.consultorioId()));
		if (candidato.vigenciaHasta().isAfter(hoy.plusDays(anticipacionDias))) {
			return Resultado.SIN_CAMBIOS;
		}
		return transaccion.execute(estado -> renovarEnTransaccion(candidato, hoy, ahora));
	}

	private Resultado renovarEnTransaccion(CandidatoARenovar candidato, LocalDate hoy, Instant ahora) {
		long organizationId = candidato.organizationId();
		long consultorioId = candidato.consultorioId();
		Optional<ProductoServicio> producto =
				productos.lockByIdInScope(organizationId, consultorioId, candidato.productoId());
		PaseServicio anterior = pases.lockByIdInScope(organizationId, consultorioId, candidato.paseId())
				.orElse(null);
		if (anterior == null || producto.isEmpty() || !anterior.isRenovacionAutomatica()
				|| anterior.getTipo() != TipoProducto.ABONO || anterior.getEstado() == EstadoPase.ANULADO) {
			// Otra instancia lo renovo, o alguien la apago o lo anulo entre la lista y el lock.
			return Resultado.SIN_CAMBIOS;
		}
		if (pases.renovacionDe(organizationId, anterior.getId()).isPresent()) {
			// Renovado y con la marca prendida: no deberia pasar (las dos cosas van en la misma
			// transaccion), pero si pasa no se vende otro periodo: se apaga y listo.
			pases.marcarRenovado(organizationId, anterior.getId());
			return Resultado.SIN_CAMBIOS;
		}

		ProductoServicio vigente = producto.get();
		LocalDate[] periodo = PaseServicio.periodoSiguiente(
				anterior.getVigenciaDesde(), anterior.getVigenciaHasta(), vigente.getVigenciaDias());
		Optional<String> impedimento = impedimento(vigente, anterior, periodo[0], periodo[1], hoy);
		if (impedimento.isPresent()) {
			omitir(anterior, impedimento.get(), ahora);
			return Resultado.OMITIDO;
		}

		PaseServicio nuevo = pases.saveAndFlush(
				PaseServicio.renovar(vigente, anterior, periodo[0], periodo[1], ahora));
		long autorizadaPor = nuevo.getVendidoPorCuentaId();
		movimientos.registrar(MovimientoPase.compra(nuevo, autorizadaPor, ahora));
		DeudaDeVenta deuda = devengo.devengarPorVentaDeAbono(new VentaDePase(
				organizationId, consultorioId, nuevo.getId(), nuevo.getPersonaId(),
				nuevo.getSnapshotOfertaId(), nuevo.getSnapshotNombre(), nuevo.getSnapshotPrecio(),
				nuevo.getSnapshotMoneda(), autorizadaPor, ahora));
		if (pases.marcarRenovado(organizationId, anterior.getId()) != 1) {
			// Imposible con el abono bloqueado; si pasa, que el rollback deshaga la venta entera.
			throw new IllegalStateException("El abono " + anterior.getId() + " cambio con el lock tomado");
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("renuevaPaseId", String.valueOf(anterior.getId()));
		detalles.put("autorizadaPorCuentaId", String.valueOf(autorizadaPor));
		detalles.put("periodoDesde", periodo[0].toString());
		detalles.put("periodoHasta", periodo[1].toString());
		detalles.put("precio", nuevo.getSnapshotPrecio().toPlainString());
		detalles.put("moneda", nuevo.getSnapshotMoneda());
		detalles.put("obligacionId", String.valueOf(deuda.obligacionId()));
		// Actor nulo: lo hizo el sistema. Quien lo autorizo va en los detalles.
		auditTrail.record(new AuditEntry(
				organizationId, consultorioId, null, "ABONO_RENOVADO", ENTIDAD, nuevo.getId(), null,
				nuevo.getEstado().name(), detalles, null, null, ahora));
		log.info("Abono renovado: paseId={} renueva={} periodo={}..{} obligacionId={}",
				nuevo.getId(), anterior.getId(), periodo[0], periodo[1], deuda.obligacionId());
		return Resultado.RENOVADO;
	}

	/**
	 * Lo mismo que la venta manual rechazaria, con el mismo texto: producto que no se vende,
	 * periodo incoherente (en particular, uno que ya termino porque el job estuvo caido mas que un
	 * periodo entero), persona de baja y periodo ya ocupado.
	 */
	private Optional<String> impedimento(
			ProductoServicio producto, PaseServicio anterior, LocalDate desde, LocalDate hasta,
			LocalDate hoy) {

		Optional<String> delProducto = producto.impedimentoParaVenderEl(hoy);
		if (delProducto.isPresent()) {
			return delProducto.map(m -> "el producto " + m);
		}
		Optional<String> delPeriodo = PaseServicio.impedimentoDelPeriodo(producto, desde, hasta, hoy);
		if (delPeriodo.isPresent()) {
			return delPeriodo.map(m -> "el periodo siguiente (" + desde + " a " + hasta + "): " + m);
		}
		Optional<PacienteSnapshot> persona = personas.find(anterior.getOrganizationId(), anterior.getPersonaId());
		if (persona.isEmpty() || !persona.get().activa()) {
			return Optional.of("la persona esta dada de baja");
		}
		if (pases.existeAbonoSuperpuesto(
				anterior.getOrganizationId(), anterior.getPersonaId(), producto.getId(), desde, hasta)) {
			return Optional.of("la persona ya tiene ese abono en un periodo que se superpone con "
					+ desde + " a " + hasta);
		}
		return Optional.empty();
	}

	private void omitir(PaseServicio anterior, String motivo, Instant ahora) {
		String recortado = motivo.length() > 280 ? motivo.substring(0, 280) : motivo;
		pases.omitirRenovacion(anterior.getOrganizationId(), anterior.getId(), recortado);
		auditTrail.record(new AuditEntry(
				anterior.getOrganizationId(), anterior.getConsultorioId(), null, "ABONO_RENOVACION_OMITIDA",
				ENTIDAD, anterior.getId(), "RENOVACION_ACTIVA", "RENOVACION_INACTIVA",
				Map.of("vigenciaHasta", anterior.getVigenciaHasta().toString()), recortado, null, ahora));
		log.info("Renovacion de abono omitida: paseId={} motivo={}", anterior.getId(), recortado);
	}
}
