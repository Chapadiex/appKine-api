package com.akine.offering.application;

import com.akine.offering.domain.OfertaServicioConsultorio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Proyeccion de lectura de una Oferta. Es lo unico que cruza el borde del servicio de aplicacion:
 * la entity nunca sale (AGENT.md seccion 4, regla 6).
 *
 * <p><b>Lleva {@code organizationId} y {@code consultorioId}, al reves que {@link ServicioView}</b>,
 * y la asimetria es el objetivo entero de la etapa: un Servicio es global y no pertenece a nadie
 * (ADR-0023), una Oferta pertenece siempre a una sede real. Una vista de oferta sin sede seria
 * indistinguible de la de otro centro que ofrece el mismo concepto, que es exactamente lo que
 * CA-M03-006-06 pide poder distinguir.
 *
 * <p><b>No trae ningun dato del {@code Servicio} —ni su nombre ni su codigo—</b>, y es deliberado:
 * la oferta guarda su propia configuracion y no vuelve a mirar al servicio despues de creada
 * (RF-M06-006). Si la pantalla necesita mostrar el nombre del concepto, lo pide al catalogo global
 * con {@link ServicioView}, que es una lectura autenticada y sin restriccion. Resolverlo aca
 * abriria justamente el canal por el que los defaults se filtrarian en cada lectura.
 *
 * @param estado      DERIVADO de {@code active}, no una columna
 * @param vigenteHoy  DERIVADO: estado ACTIVO <b>y</b> dentro de la ventana operativa. Son dos ejes
 *                    temporales distintos y esta bandera los colapsa solo para mostrar; quien
 *                    necesite decidir mira {@code estado} y las dos fechas
 * @param version     la que hay que reenviar para editar
 */
public record OfertaView(
		long id,
		long organizationId,
		long consultorioId,
		long servicioId,
		String nombreComercial,
		String descripcion,
		String modalidad,
		int duracionMinutos,
		int capacidad,
		BigDecimal precioBase,
		String moneda,
		String esquemaCobro,
		boolean admiteObraSocial,
		boolean requiereCasoClinico,
		boolean generaRegistroClinico,
		boolean requiereProfesional,
		boolean requiereEspacio,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		String estado,
		boolean vigenteHoy,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static OfertaView de(OfertaServicioConsultorio oferta, LocalDate hoy) {
		return new OfertaView(
				oferta.getId(),
				oferta.getOrganizationId(),
				oferta.getConsultorioId(),
				oferta.getServicioId(),
				oferta.getNombreComercial(),
				oferta.getDescripcion(),
				oferta.getModalidad().name(),
				oferta.getDuracionMinutos(),
				oferta.getCapacidad(),
				oferta.getPrecioBase(),
				oferta.getMoneda(),
				// Se muestra tal cual se declaro: nadie lo interpreta (RN-M27-006, EsquemaCobro).
				oferta.getEsquemaCobro() == null ? null : oferta.getEsquemaCobro().valor(),
				oferta.isAdmiteObraSocial(),
				oferta.isRequiereCasoClinico(),
				oferta.isGeneraRegistroClinico(),
				oferta.isRequiereProfesional(),
				oferta.isRequiereEspacio(),
				oferta.getVigenciaDesde(),
				oferta.getVigenciaHasta(),
				oferta.isActive() ? "ACTIVO" : "INACTIVO",
				oferta.estaVigente(hoy),
				oferta.getDeletedAt(),
				oferta.getDeactivationReason(),
				oferta.getVersion());
	}
}
