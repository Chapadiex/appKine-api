package com.akine.offering.domain;

/**
 * Como se presta una atencion: a una persona sola o a un grupo (RF-M06-006).
 *
 * <p>Aparece dos veces en el modelo y con un significado distinto cada vez, y es importante no
 * confundirlas:
 *
 * <pre>
 *   Servicio.modalidadDefault    PROPUESTA INICIAL para cuando se crea una Oferta. RF-M06-006:
 *                                "los defaults no reemplazan la configuracion concreta de cada
 *                                Oferta". Cambiarla en el Servicio no toca ninguna Oferta ya
 *                                creada: ver {@link Servicio} y el test que lo verifica.
 *
 *   OfertaServicioConsultorio.modalidad   LA QUE MANDA. Determina el CHECK
 *                                {@code ck_oferta_grupal_capacidad}: si es GRUPAL, la capacidad
 *                                tiene que ser mayor a uno (una oferta grupal de capacidad uno es
 *                                una individual mal rotulada).
 * </pre>
 *
 * <p>Los valores replican exactamente {@code ck_servicio_modalidad_default} y
 * {@code ck_oferta_modalidad} de la migracion V24, que son el mismo conjunto cerrado.
 */
public enum Modalidad {

	/** Una persona por atencion. */
	INDIVIDUAL,

	/** Varias personas simultaneas en la misma atencion. */
	GRUPAL
}
