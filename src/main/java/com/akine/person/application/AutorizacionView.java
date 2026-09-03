package com.akine.person.application;

import com.akine.person.domain.Autorizacion;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una autorizacion tal como sale del backend, con su saldo (RF-M17-003) y sus alertas
 * (RF-M17-006).
 *
 * <p><b>{@code cantidadConsumida} vale siempre cero en esta etapa</b>, y por lo tanto
 * {@code saldo} vale siempre lo autorizado. No es un bug: RN-M17-001 separa autorizado de
 * consumido, y quien mueve el consumo es la sesion clinica en una integracion posterior. Se
 * devuelven los tres numeros porque RF-M17-003 los pide y porque el dia que la resta se mueva no
 * cambia el contrato.
 *
 * <p>{@code vigente}, {@code vencida}, {@code agotada}, {@code habilita} y {@code diasParaVencer}
 * se calculan contra la fecha que pregunta. {@code estado} es lo unico persistido, y solo toma los
 * cuatro valores que decide una persona.
 *
 * <p>Las seis columnas {@code convenio*} y {@code requeria*} son la COPIA congelada de lo que el
 * convenio exigia al registrar. Vienen todas en nulo cuando no habia convenio resoluble ese dia, y
 * eso es un estado legitimo: ver {@code Autorizacion}.
 */
public record AutorizacionView(
		long id,
		long personaId,
		long consultorioId,
		long coberturaId,
		Long ordenMedicaId,
		long practicaId,
		String numero,
		String estadoAutorizacion,
		String motivo,
		Integer cantidadAutorizada,
		int cantidadConsumida,
		Integer saldo,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean vigente,
		boolean vencida,
		boolean agotada,
		boolean habilita,
		Long diasParaVencer,
		Long convenioId,
		String convenioCodigo,
		String convenioNombre,
		Boolean requeriaOrden,
		Boolean requeriaAutorizacion,
		Boolean requeriaCredencial,
		Instant referenciaCapturadaEl,
		Long adjuntoId,
		String observaciones,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static AutorizacionView de(Autorizacion autorizacion, LocalDate fecha) {
		return new AutorizacionView(
				autorizacion.getId(),
				autorizacion.getPersonaId(),
				autorizacion.getConsultorioId(),
				autorizacion.getCoberturaId(),
				autorizacion.getOrdenMedicaId(),
				autorizacion.getPracticaId(),
				autorizacion.getNumero(),
				autorizacion.getEstado().name(),
				autorizacion.getMotivo(),
				autorizacion.getCantidadAutorizada(),
				autorizacion.getCantidadConsumida(),
				autorizacion.saldo(),
				autorizacion.getVigenciaDesde(),
				autorizacion.getVigenciaHasta(),
				autorizacion.vigencia().cubre(fecha),
				autorizacion.vencidaEl(fecha),
				autorizacion.agotada(),
				autorizacion.habilitaEl(fecha),
				autorizacion.diasParaVencer(fecha),
				autorizacion.getConvenioId(),
				autorizacion.getConvenioCodigo(),
				autorizacion.getConvenioNombre(),
				autorizacion.getRequeriaOrden(),
				autorizacion.getRequeriaAutorizacion(),
				autorizacion.getRequeriaCredencial(),
				autorizacion.getReferenciaCapturadaEl(),
				autorizacion.getAdjuntoId(),
				autorizacion.getObservaciones(),
				autorizacion.isActive() ? "ACTIVA" : "INACTIVA",
				autorizacion.getDeletedAt(),
				autorizacion.getDeactivationReason(),
				autorizacion.getVersion());
	}
}
