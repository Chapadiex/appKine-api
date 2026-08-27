package com.akine.offering.application;

import com.akine.offering.domain.EsquemaCobro;
import com.akine.offering.domain.Modalidad;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edicion PARCIAL de una Oferta: cada campo {@code null} deja el valor como estaba (RF-M03-006).
 *
 * <h2>Los dos campos que NO estan, y no es un olvido</h2>
 *
 * <p><b>{@code servicioId} y {@code consultorioId} no existen en este comando</b>, ruling R3 de la
 * etapa y {@code updatable = false} en la entidad. Mover una oferta a otro servicio o a otra sede
 * no es editarla: cambiaria retroactivamente que concepto dice haber prestado y donde, y todo lo
 * que ya la referencia pasaria a significar otra cosa sin que nadie lo haya pedido. Lo correcto es
 * dar de baja esta y crear otra.
 *
 * <p>Se verifica sobre la FORMA del record y no sobre el resultado de una llamada, porque lo que
 * hay que impedir es que el campo llegue a existir: con el campo puesto, olvidarse de ignorarlo en
 * el servicio es un descuido de una linea. Es la misma tecnica que
 * {@code el_codigo_de_un_servicio_no_se_puede_editar} usa en la Tarea 4.
 *
 * <h2>Por que hacen falta tres banderas de "limpiar"</h2>
 *
 * <p>Un solo parametro nulable no puede expresar dos intenciones distintas: "no toques este campo"
 * y "sacale el valor, que quede vacio". Los tres campos opcionales de la Oferta necesitan poder
 * decir las dos cosas, y por eso cada uno lleva su bandera. Misma convencion que
 * {@code EspacioEdicionCommand.clearValidUntil}.
 *
 * <p>{@link #limpiarPrecio} limpia precio Y moneda a la vez, y es deliberado: viajan juntos
 * ({@code ck_oferta_precio_con_moneda}), y limpiar uno solo dejaria el estado "precio sin moneda"
 * que la entidad rechaza.
 */
public record OfertaEdicionCommand(

		String nombreComercial,

		String descripcion,

		Modalidad modalidad,

		Integer duracionMinutos,

		Integer capacidad,

		BigDecimal precioBase,

		String moneda,

		/** Deja la oferta sin precio: limpia {@code precioBase} Y {@code moneda}. */
		boolean limpiarPrecio,

		EsquemaCobro esquemaCobro,

		/** Deja la oferta sin esquema de cobro declarado. */
		boolean limpiarEsquemaCobro,

		Boolean admiteObraSocial,

		Boolean requiereCasoClinico,

		Boolean generaRegistroClinico,

		Boolean requiereProfesional,

		Boolean requiereEspacio,

		LocalDate vigenciaDesde,

		LocalDate vigenciaHasta,

		/** Deja la oferta sin fin de vigencia previsto, que es un estado real (RN-M27-006). */
		boolean limpiarVigenciaHasta,

		/**
		 * La {@code version} que el cliente leyo. Si quedo vieja, la edicion se rechaza con 409
		 * {@code conflict} y no pisa el cambio ajeno. Ver {@code OfertaService.exigirVersion}.
		 */
		long expectedVersion) {
}
