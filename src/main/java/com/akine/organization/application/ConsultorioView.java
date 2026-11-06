package com.akine.organization.application;

import com.akine.organization.domain.Consultorio;
import com.akine.organization.domain.Organization;
import com.akine.organization.domain.UbicacionOrigen;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Proyeccion de lectura de un consultorio.
 *
 * <p>Nacio minima en 01.01 —lo justo para armar el contexto de trabajo— y AKINE-02.01 la
 * expande con la configuracion de la sede. V140 la vuelve a expandir con la direccion
 * estructurada, la ubicacion, las opciones y los datos fiscales EFECTIVOS. Se agregaron campos;
 * ninguno de los originales cambio de nombre ni de significado, asi que el cambio es aditivo
 * tambien para el contrato.
 *
 * <p>{@code estado} es DERIVADO y no una columna: ver el javadoc de {@link Consultorio}. Lo mismo
 * {@code direccionFormateada}, {@code ubicacionPendiente} y {@code datosFiscalesEfectivos}: se
 * calculan al leer para que la pantalla no repita en TypeScript una regla que el backend ya
 * decidio.
 *
 * @param deletedAt              instante UTC de la baja, o {@code null} si la sede esta activa
 * @param deactivationReason     motivo declarado de la baja (RF-M03-004), o {@code null}
 * @param version                la que hay que reenviar para editar. Sin ella dos ediciones
 *                               concurrentes se pisan en silencio
 * @param addressLine            direccion FISCAL de la sede (texto libre). La fisica es la
 *                               estructurada
 * @param ubicacion              coordenadas, o {@code null} si la sede no esta ubicada
 * @param direccionFormateada    la direccion fisica en una linea, o {@code null} si no hay partes
 * @param ubicacionPendiente     hay direccion escrita y ninguna coordenada
 * @param datosFiscalesEfectivos con que datos factura la sede: los suyos o los de la organizacion
 */
public record ConsultorioView(
		long id,
		long organizationId,
		String name,
		String timezone,
		int slotMinutes,
		String legalName,
		String taxId,
		String addressLine,
		String phone,
		String contactEmail,
		boolean active,
		Instant deletedAt,
		String deactivationReason,
		long version,
		String alias,
		String pais,
		String provincia,
		String ciudad,
		String calle,
		String numero,
		String codigoPostal,
		String piso,
		String referencias,
		UbicacionView ubicacion,
		String direccionFormateada,
		boolean ubicacionPendiente,
		boolean agendaHabilitada,
		boolean direccionPublica,
		boolean datosFiscalesPropios,
		DatosFiscalesEfectivosView datosFiscalesEfectivos) {

	/**
	 * La forma anterior a V140: sin direccion, con agenda habilitada y con los defaults de la entity
	 * (direccion publica, datos fiscales heredados de la organizacion).
	 */
	public ConsultorioView(
			long id,
			long organizationId,
			String name,
			String timezone,
			int slotMinutes,
			String legalName,
			String taxId,
			String addressLine,
			String phone,
			String contactEmail,
			boolean active,
			Instant deletedAt,
			String deactivationReason,
			long version) {
		this(id, organizationId, name, timezone, slotMinutes, legalName, taxId, addressLine, phone,
				contactEmail, active, deletedAt, deactivationReason, version,
				null, null, null, null, null, null, null, null, null,
				null, null, false, true, true, false,
				new DatosFiscalesEfectivosView(legalName, taxId, addressLine, OrigenFiscal.ORGANIZACION));
	}

	/**
	 * Proyeccion con los datos fiscales de la organizacion a mano, para resolver los efectivos.
	 *
	 * @param organization la organizacion de la sede, o {@code null} si no se cargo: en ese caso
	 *                     una sede sin datos propios publica los suyos (vacios)
	 */
	public static ConsultorioView de(Consultorio consultorio, Organization organization) {
		return new ConsultorioView(
				consultorio.getId(),
				consultorio.getOrganizationId(),
				consultorio.getName(),
				consultorio.getTimezone(),
				consultorio.getSlotMinutes(),
				consultorio.getLegalName(),
				consultorio.getTaxId(),
				consultorio.getAddressLine(),
				consultorio.getPhone(),
				consultorio.getContactEmail(),
				consultorio.isActive(),
				consultorio.getDeletedAt(),
				consultorio.getDeactivationReason(),
				consultorio.getVersion(),
				consultorio.getAlias(),
				consultorio.getPais(),
				consultorio.getProvincia(),
				consultorio.getCiudad(),
				consultorio.getCalle(),
				consultorio.getNumero(),
				consultorio.getCodigoPostal(),
				consultorio.getPiso(),
				consultorio.getReferencias(),
				consultorio.getLatitud() == null ? null : new UbicacionView(
						consultorio.getLatitud(), consultorio.getLongitud(), consultorio.getUbicacionOrigen()),
				consultorio.direccionFormateada(),
				consultorio.isUbicacionPendiente(),
				consultorio.isAgendaHabilitada(),
				consultorio.isDireccionPublica(),
				consultorio.isDatosFiscalesPropios(),
				datosFiscalesEfectivos(consultorio, organization));
	}

	/** Sin la organizacion: los datos fiscales efectivos de una sede heredera quedan vacios. */
	public static ConsultorioView de(Consultorio consultorio) {
		return de(consultorio, null);
	}

	private static DatosFiscalesEfectivosView datosFiscalesEfectivos(
			Consultorio consultorio, Organization organization) {
		if (consultorio.isDatosFiscalesPropios() || organization == null) {
			return new DatosFiscalesEfectivosView(
					consultorio.getLegalName(), consultorio.getTaxId(), consultorio.getAddressLine(),
					OrigenFiscal.SEDE);
		}
		return new DatosFiscalesEfectivosView(
				organization.getLegalName(), organization.getTaxId(), organization.getFiscalAddressLine(),
				OrigenFiscal.ORGANIZACION);
	}

	/**
	 * Estado de la sede, calculado.
	 *
	 * <p>Dos valores y ninguna columna. Un tercer estado —"suspendido por refaccion"— no lo pide
	 * ningun RF de M03 y es una decision abierta (D-3).
	 */
	public String estado() {
		return active ? "ACTIVO" : "INACTIVO";
	}

	/** Coordenadas de la sede y quien las puso. */
	public record UbicacionView(BigDecimal latitud, BigDecimal longitud, UbicacionOrigen origen) {
	}

	/** De donde salen los datos fiscales con los que factura la sede. */
	public enum OrigenFiscal {
		SEDE,
		ORGANIZACION
	}

	/** Los datos fiscales que rigen para la sede, ya resueltos. */
	public record DatosFiscalesEfectivosView(
			String legalName, String taxId, String addressLine, OrigenFiscal origen) {
	}
}
