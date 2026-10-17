package com.akine.billing.infrastructure;

import com.akine.billing.application.AuditoriaDeObligacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.billing.spi.DevengoDeClases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * La deuda de una clase asistida (U-39, RF-M18-008), escrita por el dueno de {@code obligacion}.
 *
 * <p>{@code MANDATORY} por lo mismo que {@link DevengoDeVentasAdapter}: la deuda solo tiene
 * sentido dentro de la transaccion que registra o corrige la asistencia, y una segunda conexion
 * ({@code REQUIRES_NEW}) es la traba del pool que midio G-10. La auditoria va en la misma
 * transaccion, como en el devengo por sesion y por venta.
 */
@Component
class DevengoDeClasesAdapter implements DevengoDeClases {

	private static final Logger log = LoggerFactory.getLogger(DevengoDeClasesAdapter.class);

	private final ObligacionRepositoryPort obligaciones;
	private final AuditoriaDeObligacion auditoria;

	DevengoDeClasesAdapter(ObligacionRepositoryPort obligaciones, AuditoriaDeObligacion auditoria) {
		this.obligaciones = obligaciones;
		this.auditoria = auditoria;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public DeudaDeClase devengar(ClaseAsistida asistida, boolean buscarPrevia) {
		if (buscarPrevia) {
			Optional<Obligacion> viva =
					obligaciones.findVivaDeLaAsistencia(asistida.organizationId(), asistida.asistenciaId());
			if (viva.isPresent()) {
				return deuda(DeudaDeClase.Efecto.YA_DEVENGADA, viva.get());
			}
		}

		Obligacion obligacion = obligaciones.saveAndFlush(Obligacion.porAsistenciaAClase(
				asistida.organizationId(), asistida.consultorioId(), asistida.asistenciaId(),
				asistida.claseId(), asistida.personaId(), asistida.importe(), asistida.moneda(),
				asistida.ofertaId(), asistida.nombre(), asistida.ocurridaEn()));
		auditoria.devengada(obligacion, asistida.actorCuentaId(), asistida.ocurridaEn());
		log.info("Obligacion devengada por clase asistida: obligacionId={} asistenciaId={} claseId={} "
						+ "importe={} {}",
				obligacion.getId(), asistida.asistenciaId(), asistida.claseId(), asistida.importe(),
				asistida.moneda());
		return deuda(DeudaDeClase.Efecto.DEVENGADA, obligacion);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public DeudaDeClase anular(
			long organizationId, long asistenciaId, long actorCuentaId, String motivo, Instant ahora) {

		return obligaciones.lockVivaDeLaAsistencia(organizationId, asistenciaId)
				.map(viva -> anularUna(viva, actorCuentaId, motivo, ahora))
				.orElseGet(DeudaDeClase::sinDeuda);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public List<DeudaDeClase> anularPorCancelacion(
			long organizationId, long claseId, long actorCuentaId, String motivo, Instant ahora) {

		List<DeudaDeClase> resultado = new ArrayList<>();
		for (Obligacion viva : obligaciones.lockVivasDeLaClase(organizationId, claseId)) {
			resultado.add(anularUna(viva, actorCuentaId, motivo, ahora));
		}
		return resultado;
	}

	/**
	 * La regla de {@code Obligacion#anular}, preguntada antes para no lanzar: con cobros imputados
	 * no se escribe nada y se informa. La fila esta tomada {@code FOR UPDATE}, asi que el saldo que
	 * se mira es el actual y un cobro concurrente espera a que esta transaccion termine.
	 */
	private DeudaDeClase anularUna(Obligacion viva, long actorCuentaId, String motivo, Instant ahora) {
		if (viva.tieneCobros()) {
			log.info("Deuda de clase con cobros: no se anula. obligacionId={} asistenciaId={}",
					viva.getId(), viva.getAsistenciaId());
			return deuda(DeudaDeClase.Efecto.CON_COBROS, viva);
		}
		String estadoAnterior = viva.getEstado().name();
		viva.anular(motivo, ahora, actorCuentaId);
		Obligacion anulada = obligaciones.saveAndFlush(viva);
		auditoria.anulada(anulada, estadoAnterior, actorCuentaId, motivo, ahora);
		log.info("Deuda de clase anulada: obligacionId={} asistenciaId={}",
				anulada.getId(), anulada.getAsistenciaId());
		return deuda(DeudaDeClase.Efecto.ANULADA, anulada);
	}

	private static DeudaDeClase deuda(DeudaDeClase.Efecto efecto, Obligacion obligacion) {
		return new DeudaDeClase(efecto, obligacion.getId(), obligacion.getImporteOriginal(),
				obligacion.getSaldo(), obligacion.getMoneda(), obligacion.getEstado().name());
	}
}
