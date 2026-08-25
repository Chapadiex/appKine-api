package com.akine.resource.domain;

/**
 * Clasificacion fisica de un espacio (RF-M04-007).
 *
 * <h2>Que es y, sobre todo, que NO es</h2>
 *
 * <p><b>No habilita servicios.</b> RN-M04-007 es explicita: un espacio se habilita para
 * determinados servicios "sin crear roles o tipos rigidos por nombre". Que una sala se llame
 * {@code SALA_GRUPAL} no decide que ahi se dan clases de Pilates: eso lo resolvera la
 * habilitacion por Oferta de Servicio (RF-M04-008, modulo {@code offering}). Este enum es
 * descriptivo —sirve para agrupar en un listado y para que la pantalla muestre un icono— y
 * cualquier regla de negocio que lo use como condicion esta violando RN-M04-007.
 *
 * <p><b>No sustituye a la capacidad.</b> Un {@code BOX} normalmente tiene capacidad 1 y un
 * {@code GIMNASIO} mas, pero eso es una costumbre y no una regla: la capacidad es una columna
 * propia y configurable (RN-M04-005), y derivarla del tipo dejaria sin representar el box
 * doble o el gimnasio chico.
 *
 * <p>Los valores replican exactamente {@code ck_espacio_tipo} de la migracion V19. Agregar uno
 * exige migracion, y eso es deliberado: el conjunto es del producto, no del tenant.
 */
public enum EspacioTipo {

	/** Consultorio individual de atencion. La capacidad tipica es 1. */
	BOX,

	/** Salon de trabajo con equipamiento, para varias personas simultaneas. */
	GIMNASIO,

	/** Espacio cerrado para practicas especificas. */
	GABINETE,

	/** Sala preparada para actividades grupales: Pilates, funcional, kinesiologia grupal. */
	SALA_GRUPAL,

	/** Pileta de rehabilitacion acuatica. */
	PILETA,

	/**
	 * Cualquier otro recurso fisico reservable.
	 *
	 * <p>Es el fallback explicito y no un descuido: sin el, un centro con un recurso que no
	 * encaja termina eligiendo el valor menos malo y arruinando cualquier agrupacion posterior.
	 */
	OTRO
}
