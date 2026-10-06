package com.akine.offering.api.dto;

import com.akine.offering.application.HabilitacionesView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Como esta configurada una Oferta: quien puede prestarla, donde, y con que capacidad real.
 *
 * <p><b>{@code restringidaPorProfesional} y {@code restringidaPorEspacio} son campos propios y no
 * se deducen de la lista.</b> Una lista vacia significa <b>sin restringir</b>, no "nadie";
 * deducirlo obligaria a cada consumidor a conocer esa regla, y el dia que uno la lea al reves va a
 * mostrar "ningun profesional habilitado" sobre una oferta que cualquiera puede prestar.
 */
@Schema(description = "Profesionales y espacios habilitados de una oferta, con su capacidad real")
public record HabilitacionesResponse(

		@Schema(description = "Oferta configurada", example = "34")
		long ofertaId,

		@Schema(
				description = "Version de la OFERTA que hay que mandar como expectedVersion en el "
						+ "proximo reemplazo. En la lectura es la vigente; en la respuesta de un "
						+ "reemplazo es la que la oferta queda teniendo despues de ese reemplazo, "
						+ "asi que dos reemplazos se encadenan sin releer",
				example = "3")
		long ofertaVersion,

		@Schema(
				description = "Si la oferta esta restringida a una lista de profesionales. false "
						+ "significa que cualquier profesional con vinculo vigente puede "
						+ "prestarla, NO que no pueda ninguno")
		boolean restringidaPorProfesional,

		@Schema(
				description = "Si la oferta esta restringida a una lista de espacios. false "
						+ "significa que puede prestarse en cualquiera, NO en ninguno")
		boolean restringidaPorEspacio,

		@Schema(description = "Habilitaciones de profesional, activas e inactivas")
		List<ProfesionalHabilitado> profesionales,

		@Schema(description = "Habilitaciones de espacio, activas e inactivas")
		List<EspacioHabilitado> espacios,

		@Schema(description = "Capacidad que declara la oferta", example = "8")
		int capacidadComercial,

		@Schema(
				description = "Minimo entre la comercial y la de los espacios habilitados que "
						+ "estan en servicio. Sin espacios habilitados es igual a la comercial: "
						+ "no hay ningun espacio concreto contra el cual acotarla todavia",
				example = "6")
		int capacidadEfectiva,

		@Schema(
				description = "Nombre del espacio que fija la efectiva, o null si ninguno la "
						+ "acota. Un numero mas chico sin este dato es un defecto de la pantalla",
				example = "Sala grupal chica")
		String espacioQueLimita) {

	/** Un profesional habilitado. */
	@Schema(description = "Profesional habilitado para prestar la oferta")
	public record ProfesionalHabilitado(

			@Schema(description = "Identificador de la habilitacion", example = "12")
			long id,

			@Schema(description = "Vinculo del profesional con la organizacion", example = "215")
			long membershipId,

			@Schema(description = "Nombre de la persona. Null si su cuenta ya no se puede resolver")
			String nombre,

			@Schema(description = "Rol de ese vinculo", example = "PROFESIONAL")
			String roleCode,

			@Schema(description = "Instante UTC desde el que la habilitacion rige")
			Instant validFrom,

			@Schema(description = "Instante UTC hasta el que rige, EXCLUSIVO. Null = sin fin")
			Instant validUntil,

			@Schema(description = "Ciclo de vida de la habilitacion", example = "ACTIVO",
					allowableValues = {"ACTIVO", "INACTIVO"})
			String estado,

			@Schema(
					description = "Si la habilitacion rige AHORA. Distinto de estado: una activa "
							+ "que arranca el mes que viene tiene estado=ACTIVO y "
							+ "vigenteHoy=false")
			boolean vigenteHoy,

			@Schema(
					description = "Si la membership sigue habilitando a esa persona en la sede. "
							+ "Puede ser false con la habilitacion intacta: el colaborador se "
							+ "desvinculo y la fila quedo colgando. Se muestra, no se esconde")
			boolean vinculoVigente,

			@Schema(description = "Instante UTC de la baja. Null mientras este activa")
			Instant deletedAt,

			@Schema(description = "Motivo declarado de la baja")
			String deactivationReason,

			@Schema(description = "Version de la habilitacion", example = "0")
			long version) {
	}

	/** Un espacio habilitado. */
	@Schema(description = "Espacio en el que la oferta puede prestarse")
	public record EspacioHabilitado(

			@Schema(description = "Identificador de la habilitacion", example = "7")
			long id,

			@Schema(description = "Espacio habilitado", example = "3")
			long espacioId,

			@Schema(description = "Nombre del espacio. Null si ya no se puede resolver")
			String nombre,

			@Schema(description = "Tipo fisico del espacio", example = "SALA_GRUPAL")
			String tipo,

			@Schema(description = "Personas que admite el espacio", example = "6")
			int capacidad,

			@Schema(description = "Instante UTC desde el que la habilitacion rige")
			Instant validFrom,

			@Schema(description = "Instante UTC hasta el que rige, EXCLUSIVO. Null = sin fin")
			Instant validUntil,

			@Schema(description = "Ciclo de vida de la habilitacion", example = "ACTIVO",
					allowableValues = {"ACTIVO", "INACTIVO"})
			String estado,

			@Schema(description = "Si la habilitacion rige AHORA")
			boolean vigenteHoy,

			@Schema(
					description = "Si el espacio sigue operativo. Puede ser false con la "
							+ "habilitacion intacta: el espacio se dio de baja en su propio "
							+ "modulo y esta fila quedo apuntandolo. Se muestra con su motivo, "
							+ "porque esconderla dejaria sin explicar por que la capacidad "
							+ "efectiva cambio sola")
			boolean enServicio,

			@Schema(description = "Instante UTC de la baja. Null mientras este activa")
			Instant deletedAt,

			@Schema(description = "Motivo declarado de la baja")
			String deactivationReason,

			@Schema(description = "Version de la habilitacion", example = "0")
			long version) {
	}

	public static HabilitacionesResponse de(HabilitacionesView view) {
		return new HabilitacionesResponse(
				view.ofertaId(),
				view.ofertaVersion(),
				view.restringidaPorProfesional(),
				view.restringidaPorEspacio(),
				view.profesionales().stream()
						.map(p -> new ProfesionalHabilitado(
								p.id(), p.membershipId(), p.nombre(), p.roleCode(),
								p.validFrom(), p.validUntil(), p.estado(), p.vigenteHoy(),
								p.vinculoVigente(), p.deletedAt(), p.deactivationReason(),
								p.version()))
						.toList(),
				view.espacios().stream()
						.map(e -> new EspacioHabilitado(
								e.id(), e.espacioId(), e.nombre(), e.tipo(), e.capacidad(),
								e.validFrom(), e.validUntil(), e.estado(), e.vigenteHoy(),
								e.enServicio(), e.deletedAt(), e.deactivationReason(), e.version()))
						.toList(),
				view.capacidadComercial(),
				view.capacidadEfectiva(),
				view.espacioQueLimita());
	}
}
