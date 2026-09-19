package com.akine.clinical.api.dto;

import com.akine.clinical.application.EntradaClinicaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una entrada clinica con su version VIGENTE, tal como sale por la API.
 *
 * <p>Trae una sola version —la ultima— y no el historico: consultarlo entero es un acceso mas
 * amplio, porque muestra lo que la historia decia antes y por que se corrigio, y tiene su propia
 * operacion y su propio evento de auditoria. Colapsarlo aca esconderia justamente el acceso que
 * despues alguien quiere revisar.
 *
 * <p>{@code version} es la de la CABECERA, para el control optimista de la enmienda y la baja.
 * {@code numeroVersion} es la del CONTENIDO. Son dos numeros distintos que cuentan cosas
 * distintas y el cliente no puede usar uno por el otro.
 */
@Schema(description = "Entrada clinica con su version vigente (RF-M09-006)")
public record EntradaClinicaResponse(

		@Schema(description = "Identificador de la entrada", example = "312")
		long id,

		@Schema(description = "Historia clinica de la que cuelga", example = "88")
		long historiaClinicaId,

		@Schema(description = "Clase de hecho clinico", example = "EVOLUCION",
				allowableValues = {"EVOLUCION", "INDICACION", "INTERCONSULTA", "OBSERVACION",
						"OTRO"})
		String tipo,

		@Schema(description = "Como nacio la entrada. Hoy solo existe MANUAL",
				example = "MANUAL",
				allowableValues = {"MANUAL", "SESION", "ORDEN", "EXTERNO"})
		String origen,

		@Schema(description = "Id de la entidad que la origino cuando el origen no es MANUAL")
		Long referenciaOrigen,

		@Schema(description = "Instante UTC del HECHO clinico. Puede ser anterior al de su carga "
				+ "—una evolucion se escribe al final del dia— pero nunca futuro")
		Instant ocurrioEn,

		@Schema(description = "Instante UTC en que se cargo la entrada")
		Instant registradaEn,

		@Schema(description = "Cuenta que la registro", example = "8")
		long registradaPor,

		@Schema(description = "Numero de la version vigente del CONTENIDO. La 1 es el original",
				example = "2")
		int numeroVersion,

		@Schema(description = "Texto clinico de la version vigente")
		String cuerpo,

		@Schema(description = "Por que se escribio esta version. null en la version 1: el "
				+ "original no enmienda nada")
		String motivoEnmienda,

		@Schema(description = "Instante UTC en que se escribio esta version")
		Instant contenidoRegistradoEn,

		@Schema(description = "Cuenta que escribio esta version", example = "8")
		long contenidoRegistradoPor,

		@Schema(description = "Si la entrada tiene mas de una version. El original NUNCA se "
				+ "sobrescribe: sigue consultable en el historico", example = "true")
		boolean enmendada,

		@Schema(description = "Si la entrada esta vigente. Una entrada de baja SALE del timeline "
				+ "pero se sigue consultando por su id", example = "true")
		boolean vigente,

		@Schema(description = "Instante UTC de la baja logica, o null")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja, o null")
		String deactivationReason,

		@Schema(description = "Version de la CABECERA, para el bloqueo optimista de la enmienda y "
				+ "de la baja. No es numeroVersion", example = "1")
		long version) {

	public static EntradaClinicaResponse from(EntradaClinicaView view) {
		return new EntradaClinicaResponse(
				view.id(),
				view.historiaClinicaId(),
				view.tipo(),
				view.origen(),
				view.referenciaOrigen(),
				view.ocurrioEn(),
				view.registradaEn(),
				view.registradaPor(),
				view.numeroVersion(),
				view.cuerpo(),
				view.motivoEnmienda(),
				view.contenidoRegistradoEn(),
				view.contenidoRegistradoPor(),
				view.enmendada(),
				view.vigente(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
