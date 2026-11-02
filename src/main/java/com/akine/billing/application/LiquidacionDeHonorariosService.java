package com.akine.billing.application;

import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.Egreso;
import com.akine.billing.domain.LiquidacionHonorario;
import com.akine.billing.domain.LiquidacionHonorarioClase;
import com.akine.billing.domain.TipoBeneficiario;
import com.akine.billing.domain.port.EgresoRepositoryPort;
import com.akine.billing.domain.port.LiquidacionHonorarioRepositoryPort;
import com.akine.billing.spi.LiquidacionesDeHonorarios.ClaseLiquidada;
import com.akine.billing.spi.LiquidacionesDeHonorarios.Liquidacion;
import com.akine.billing.spi.LiquidacionesDeHonorarios.LiquidacionAConfirmar;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * La liquidacion de honorarios por clase del lado de {@code billing} (AKINE-08.09b, DP-38).
 *
 * <p><b>No calcula nada.</b> Recibe la base ya resuelta por {@code activity}, crea el egreso de M22
 * por el camino del egreso manual y congela el detalle por clase (CA-M22-007-06). Ninguna de sus
 * operaciones abre transaccion propia: corren en la del llamador, que ya tomo el lock de las
 * clases y exigio {@code caja:operate}.
 */
@Service
public class LiquidacionDeHonorariosService {

	private static final Logger log = LoggerFactory.getLogger(LiquidacionDeHonorariosService.class);

	private final LiquidacionHonorarioRepositoryPort liquidaciones;
	private final EgresoRepositoryPort egresos;
	private final EgresoService egresoService;
	private final AuditTrail auditTrail;

	public LiquidacionDeHonorariosService(
			LiquidacionHonorarioRepositoryPort liquidaciones,
			EgresoRepositoryPort egresos,
			EgresoService egresoService,
			AuditTrail auditTrail) {

		this.liquidaciones = liquidaciones;
		this.egresos = egresos;
		this.egresoService = egresoService;
		this.auditTrail = auditTrail;
	}

	public Map<Long, Long> clasesLiquidadas(
			long organizationId, long colaboradorMembershipId, Collection<Long> claseIds) {

		Map<Long, Long> liquidadas = new LinkedHashMap<>();
		for (Object[] fila : liquidaciones.clasesVigentesDelColaborador(
				organizationId, colaboradorMembershipId, claseIds)) {
			liquidadas.put(((Number) fila[0]).longValue(), ((Number) fila[1]).longValue());
		}
		return liquidadas;
	}

	public boolean claseLiquidada(long organizationId, long claseId) {
		return liquidaciones.existeVigenteDeLaClase(organizationId, claseId);
	}

	public Optional<Liquidacion> porIdempotencyKey(long organizationId, String idempotencyKey) {
		return liquidaciones.findByIdempotencyKey(organizationId, idempotencyKey).map(this::leer);
	}

	public Optional<Liquidacion> ver(long organizationId, long consultorioId, long liquidacionId) {
		return liquidaciones.findByIdInScope(organizationId, consultorioId, liquidacionId).map(this::leer);
	}

	/** Egreso {@code BORRADOR} + liquidacion + detalle, en la transaccion del llamador. */
	public Liquidacion confirmar(LiquidacionAConfirmar pedido) {
		if (pedido.clases().isEmpty()) {
			throw new IllegalArgumentException("Una liquidacion sin clases no es una liquidacion");
		}
		if (pedido.clases().stream().anyMatch(c -> c.ofertaId() != pedido.ofertaId())) {
			throw new IllegalStateException(
					"Una liquidacion es de una sola oferta: su egreso se imputa a ella (DP-39)");
		}
		BigDecimal suma = pedido.clases().stream()
				.map(ClaseLiquidada::importeColaborador)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		if (suma.compareTo(pedido.importeTotal()) != 0) {
			throw new IllegalStateException("El total de la liquidacion (" + pedido.importeTotal()
					+ ") no es la suma de sus clases (" + suma + ")");
		}

		EgresoView egreso = egresoService.registrarDeLiquidacion(
				pedido.organizationId(), pedido.consultorioId(), pedido.actorCuentaId(),
				new EgresoCommand(
						CategoriaEgreso.HONORARIOS_PROFESIONALES,
						TipoBeneficiario.COLABORADOR,
						pedido.colaboradorMembershipId(),
						null,
						null,
						pedido.periodoDesde(),
						pedido.periodoHasta(),
						pedido.concepto(),
						pedido.importeTotal(),
						pedido.moneda(),
						pedido.comprobanteTipo(),
						pedido.comprobanteNumero(),
						pedido.comprobanteFecha(),
						null,
						pedido.ofertaId()));

		Instant ahora = Instant.now();
		LiquidacionHonorario liquidacion = liquidaciones.save(new LiquidacionHonorario(
				pedido.organizationId(), pedido.consultorioId(), egreso.id(),
				pedido.colaboradorMembershipId(), pedido.periodoDesde(), pedido.periodoHasta(),
				pedido.importeTotal(), pedido.moneda(), ahora, pedido.actorCuentaId(),
				pedido.idempotencyKey(), pedido.requestHash()));

		for (ClaseLiquidada clase : pedido.clases()) {
			liquidaciones.guardarClase(new LiquidacionHonorarioClase(
					pedido.organizationId(), liquidacion.getId(), clase.claseId(),
					pedido.colaboradorMembershipId(), clase.claseInicio(), clase.claseFecha(),
					clase.claseTitulo(), clase.ofertaId(), clase.ofertaNombre(), clase.presentes(),
					clase.honorarioId(), clase.importePorParticipante(), clase.importeClase(),
					clase.instructores(), clase.posicion(), clase.importeColaborador(), clase.moneda()));
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("egresoId", String.valueOf(egreso.id()));
		detalles.put("colaborador", "M:" + pedido.colaboradorMembershipId());
		detalles.put("periodo", pedido.periodoDesde() + " a " + pedido.periodoHasta());
		detalles.put("clases", String.valueOf(pedido.clases().size()));
		detalles.put("importeTotal", pedido.importeTotal().toPlainString() + " " + pedido.moneda());
		auditTrail.record(new AuditEntry(
				pedido.organizationId(),
				pedido.consultorioId(),
				pedido.actorCuentaId(),
				AuditEvents.LIQUIDACION_HONORARIOS_CONFIRMADA,
				AuditEvents.ENTITY_LIQUIDACION_HONORARIOS,
				liquidacion.getId(),
				null,
				LiquidacionHonorario.VIGENTE,
				detalles,
				null,
				AuditEvents.correlationId(),
				ahora));

		log.info("Liquidacion de honorarios confirmada: liquidacionId={} egresoId={} colaborador={} "
						+ "clases={} importe={}",
				liquidacion.getId(), egreso.id(), pedido.colaboradorMembershipId(),
				pedido.clases().size(), pedido.importeTotal());

		return leer(liquidacion);
	}

	private Liquidacion leer(LiquidacionHonorario liquidacion) {
		Optional<Egreso> egreso = egresos.findByIdInScope(
				liquidacion.getOrganizationId(), liquidacion.getConsultorioId(), liquidacion.getEgresoId());
		List<ClaseLiquidada> clases = liquidaciones
				.clasesDe(liquidacion.getOrganizationId(), liquidacion.getId()).stream()
				.map(c -> new ClaseLiquidada(
						c.getClaseId(), c.getClaseInicio(), c.getClaseFecha(), c.getClaseTitulo(),
						c.getOfertaId(), c.getOfertaNombre(), c.getPresentes(), c.getHonorarioId(),
						c.getImportePorParticipante(), c.getImporteClase(), c.getInstructores(),
						c.getPosicion(), c.getImporteColaborador(), c.getMoneda()))
				.toList();

		return new Liquidacion(
				liquidacion.getId(),
				liquidacion.getConsultorioId(),
				liquidacion.getEgresoId(),
				egreso.map(e -> e.getEstado().name()).orElse(null),
				egreso.map(Egreso::getSaldoPendiente).orElse(null),
				liquidacion.getColaboradorMembershipId(),
				egreso.map(Egreso::getBeneficiarioNombre).orElse(null),
				egreso.map(Egreso::getOfertaId).orElse(null),
				liquidacion.getPeriodoDesde(),
				liquidacion.getPeriodoHasta(),
				liquidacion.getImporteTotal(),
				liquidacion.getMoneda(),
				liquidacion.getEstado(),
				liquidacion.getConfirmadaEn(),
				liquidacion.getConfirmadaPorCuentaId(),
				liquidacion.getLiberadaEn(),
				liquidacion.getRequestHash(),
				clases);
	}
}
