package com.akine.billing.infrastructure;

import com.akine.billing.application.AuditoriaDeObligacion;
import com.akine.billing.domain.EstadoObligacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.billing.spi.DevengoDeVentas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * La deuda de la venta de un pase (RF-M18-009), escrita por el dueno de {@code obligacion}.
 *
 * <p>{@code MANDATORY} y no {@code REQUIRED}: esta deuda <b>solo</b> tiene sentido dentro de la
 * transaccion que crea el pase. Llamarla sin una seria devengar en una transaccion propia y dejar,
 * si la venta despues falla, una deuda por un pase que no existe. Tampoco {@code REQUIRES_NEW}: una
 * segunda conexion dentro de la venta es la traba del pool que midio G-10 (#91/#97).
 *
 * <p>La auditoria se escribe aca, en la misma transaccion, como en el devengo por sesion.
 */
@Component
class DevengoDeVentasAdapter implements DevengoDeVentas {

	private static final Logger log = LoggerFactory.getLogger(DevengoDeVentasAdapter.class);

	private final ObligacionRepositoryPort obligaciones;
	private final AuditoriaDeObligacion auditoria;

	DevengoDeVentasAdapter(ObligacionRepositoryPort obligaciones, AuditoriaDeObligacion auditoria) {
		this.obligaciones = obligaciones;
		this.auditoria = auditoria;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public DeudaDeVenta devengarPorVentaDePase(VentaDePase venta) {
		return devengar(venta, false);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public DeudaDeVenta devengarPorVentaDeAbono(VentaDePase venta) {
		return devengar(venta, true);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public AnulacionDeDeuda anularDeudaDeVenta(
			long organizationId, long paseId, long actorCuentaId, String motivo, Instant ahora) {

		Optional<Obligacion> encontrada = obligaciones.findDeLaVenta(organizationId, paseId);
		if (encontrada.isEmpty()) {
			return new AnulacionDeDeuda(AnulacionDeDeuda.Efecto.SIN_DEUDA, null, null, null, null, null);
		}
		Obligacion obligacion = encontrada.get();
		if (obligacion.getEstado() == EstadoObligacion.ANULADA) {
			return anulacion(AnulacionDeDeuda.Efecto.YA_ANULADA, obligacion);
		}
		// La misma regla que Obligacion#anular, preguntada antes para no escribir: con cobros
		// imputados la venta no se anula, se reintegra el cobro primero (RF-M19-010).
		if (obligacion.getSaldo().compareTo(obligacion.getImporteOriginal()) != 0) {
			return anulacion(AnulacionDeDeuda.Efecto.CON_COBROS, obligacion);
		}
		String estadoAnterior = obligacion.getEstado().name();
		obligacion.anular(motivo, ahora, actorCuentaId);
		// saveAndFlush: el UPDATE lleva la version leida. Un cobro que imputo entre la lectura y
		// aca movio la version (descontarSaldo la sube) y esto falla con conflicto de concurrencia.
		Obligacion anulada = obligaciones.saveAndFlush(obligacion);
		auditoria.anulada(anulada, estadoAnterior, actorCuentaId, motivo, ahora);
		log.info("Obligacion anulada por anulacion de venta: obligacionId={} paseId={}",
				anulada.getId(), paseId);
		return anulacion(AnulacionDeDeuda.Efecto.ANULADA, anulada);
	}

	@Override
	public Set<Long> ventasConCobros(long organizationId, Collection<Long> paseIds) {
		if (paseIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(obligaciones.ventasConCobros(organizationId, paseIds));
	}

	private DeudaDeVenta devengar(VentaDePase venta, boolean abono) {
		Optional<Obligacion> existente =
				obligaciones.findDeLaVenta(venta.organizationId(), venta.paseId());
		if (existente.isPresent()) {
			return deuda(existente.get(), false);
		}

		Obligacion nueva = abono
				? Obligacion.porVentaDeAbono(venta.organizationId(), venta.consultorioId(), venta.paseId(),
						venta.personaId(), venta.importe(), venta.moneda(), venta.ofertaId(), venta.nombre(),
						venta.ocurridaEn())
				: Obligacion.porVentaDePase(venta.organizationId(), venta.consultorioId(), venta.paseId(),
						venta.personaId(), venta.importe(), venta.moneda(), venta.ofertaId(), venta.nombre(),
						venta.ocurridaEn());
		Obligacion obligacion = obligaciones.saveAndFlush(nueva);
		auditoria.devengada(obligacion, venta.actorCuentaId(), venta.ocurridaEn());

		log.info("Obligacion devengada por venta: origen={} obligacionId={} paseId={} personaId={} "
						+ "importe={} {}",
				obligacion.getOrigen(), obligacion.getId(), venta.paseId(), venta.personaId(),
				venta.importe(), venta.moneda());
		return deuda(obligacion, true);
	}

	private static AnulacionDeDeuda anulacion(AnulacionDeDeuda.Efecto efecto, Obligacion obligacion) {
		return new AnulacionDeDeuda(efecto, obligacion.getId(), obligacion.getImporteOriginal(),
				obligacion.getSaldo(), obligacion.getMoneda(), obligacion.getEstado().name());
	}

	private static DeudaDeVenta deuda(Obligacion obligacion, boolean ahora) {
		return new DeudaDeVenta(
				obligacion.getId(), obligacion.getImporteOriginal(), obligacion.getSaldo(),
				obligacion.getMoneda(), obligacion.getEstado().name(), ahora);
	}
}
