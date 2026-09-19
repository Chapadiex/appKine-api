package com.akine.clinical.application;

import com.akine.clinical.domain.EntradaClinica;
import com.akine.clinical.domain.EntradaClinicaVersion;

import java.time.Instant;

/**
 * Una entrada clinica con su version vigente, tal como sale del servicio.
 *
 * <h2>Por que trae una sola version y no todas</h2>
 *
 * <p>Porque el caso normal es leer lo que la historia dice <b>hoy</b>. Un paciente cronico con
 * 400 entradas, varias enmendadas, devolveria miles de textos clinicos en una pantalla donde nadie
 * los va a mirar. El historico completo es una operacion aparte
 * ({@code EntradaClinicaService#versiones}), que ademas se audita como su propio acceso.
 *
 * <p>{@link #enmendada} existe para que la pantalla pueda ofrecer ese historico sin tener que
 * pedirlo primero para descubrir si hay algo que ver.
 *
 * <h2>Por que trae tambien las entradas dadas de baja</h2>
 *
 * <p>Con sus campos de baja completos, igual que {@link AntecedenteView}: la regla maestra 10 no
 * admite que un dato clinico desaparezca, y una vista que ocultara el motivo dejaria al historico
 * sin poder explicarse.
 *
 * @param version version de la CABECERA, para el control optimista de la enmienda y de la baja.
 *                No confundir con {@link #numeroVersion}, que es el orden del contenido
 */
public record EntradaClinicaView(
		long id,
		long historiaClinicaId,
		String tipo,
		String origen,
		Long referenciaOrigen,
		Instant ocurrioEn,
		Instant registradaEn,
		long registradaPor,
		int numeroVersion,
		String cuerpo,
		String motivoEnmienda,
		Instant contenidoRegistradoEn,
		long contenidoRegistradoPor,
		boolean enmendada,
		boolean vigente,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static EntradaClinicaView de(EntradaClinica entrada, EntradaClinicaVersion vigente) {
		return new EntradaClinicaView(
				entrada.getId(),
				entrada.getHistoriaClinicaId(),
				entrada.getTipo().name(),
				entrada.getOrigen().name(),
				entrada.getReferenciaOrigen(),
				entrada.getOcurrioEn(),
				entrada.getRegistradaEn(),
				entrada.getRegistradaPor(),
				vigente.getNumeroVersion(),
				vigente.getCuerpo(),
				vigente.getMotivoEnmienda(),
				vigente.getRegistradaEn(),
				vigente.getRegistradaPor(),
				entrada.fueEnmendada(),
				entrada.isVigente(),
				entrada.getDeletedAt(),
				entrada.getDeactivationReason(),
				entrada.getVersion());
	}
}
