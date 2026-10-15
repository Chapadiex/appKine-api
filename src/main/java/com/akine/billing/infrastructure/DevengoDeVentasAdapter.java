package com.akine.billing.infrastructure;

import com.akine.billing.application.AuditoriaDeObligacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.billing.spi.DevengoDeVentas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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
		Optional<Obligacion> existente =
				obligaciones.findDeLaVenta(venta.organizationId(), venta.paseId());
		if (existente.isPresent()) {
			return deuda(existente.get(), false);
		}

		Obligacion obligacion = obligaciones.saveAndFlush(Obligacion.porVentaDePase(
				venta.organizationId(),
				venta.consultorioId(),
				venta.paseId(),
				venta.personaId(),
				venta.importe(),
				venta.moneda(),
				venta.ofertaId(),
				venta.nombre(),
				venta.ocurridaEn()));
		auditoria.devengada(obligacion, venta.actorCuentaId(), venta.ocurridaEn());

		log.info("Obligacion devengada por venta de pase: obligacionId={} paseId={} personaId={} "
						+ "importe={} {}",
				obligacion.getId(), venta.paseId(), venta.personaId(), venta.importe(),
				venta.moneda());
		return deuda(obligacion, true);
	}

	private static DeudaDeVenta deuda(Obligacion obligacion, boolean ahora) {
		return new DeudaDeVenta(
				obligacion.getId(), obligacion.getImporteOriginal(), obligacion.getSaldo(),
				obligacion.getMoneda(), obligacion.getEstado().name(), ahora);
	}
}
