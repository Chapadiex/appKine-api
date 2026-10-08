package com.akine.offering.application;

import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.Naturaleza;

/**
 * Edicion parcial de un Servicio del catalogo global (RF-M27-001).
 *
 * <p>Semantica de PATCH: los campos {@code null} no se tocan. Para borrar la descripcion se manda
 * cadena vacia. Misma convencion que {@code CatalogoEdicionCommand} y {@code EspacioEdicionCommand}.
 *
 * <p><b>El codigo no esta, y no es un olvido</b> (ruling R3 de esta etapa, y
 * {@code updatable = false} en la entidad). Es la clave estable con la que se referencia el
 * servicio: mutarlo haria que una referencia vieja a {@code KINE-DEPORTIVA} pase a significar
 * otra cosa sin que nadie lo haya pedido. Editar un Servicio es cambiarle el nombre, la
 * descripcion, la clasificacion y las sugerencias — nunca su identidad.
 *
 * <p><b>Cambiar cualquiera de los tres {@code *Default} no toca ninguna Oferta ya creada.</b> La
 * copia ocurre una sola vez, al crear la oferta, y desde ahi las dos historias son
 * independientes: RF-M06-006 y RN-M06-005 lo exigen, y {@code OfertaTest} lo hace ejecutable.
 *
 * <p>{@code expectedVersion} es obligatoria y se compara antes de mutar: sin ella dos ediciones
 * simultaneas se pisan y el segundo en guardar borra el cambio del primero sin que nadie se
 * entere. Una version desactualizada responde <b>409 {@code concurrent-modification}</b> (DP-21).
 */
public record ServicioEdicionCommand(

		String nombre,

		String descripcion,

		Naturaleza naturaleza,

		Modalidad modalidadDefault,

		Boolean requiereCasoClinicoDefault,

		Boolean generaRegistroClinicoDefault,

		long expectedVersion) {
}
