package com.akine.person.application;

/**
 * Los requisitos administrativos que un convenio puede exigir (DP-08).
 *
 * <p>Son exactamente las tres banderas que {@code Convenio} declara desde 03.05 —
 * {@code requiere_orden}, {@code requiere_autorizacion} y {@code requiere_credencial}— y que hasta
 * esta etapa nadie interpretaba. La lista es cerrada y corta a proposito: DP-08 exige que ningun
 * ejemplo de las fuentes historicas se vuelva obligatorio global sin una regla confirmada, y cada
 * valor de mas seria un requisito que alguien tiene que poder configurar y hoy no puede.
 *
 * <p>El tope mensual del convenio ({@code limite_sesiones_mensual}) <b>no</b> esta aca: no es un
 * documento que se presente, es un limite de consumo, y verificarlo exige contar sesiones ya
 * atendidas. Eso es RF-M17-004 y no existe todavia. Viaja como dato informativo de la consulta,
 * no como requisito con veredicto.
 */
public enum TipoRequisito {

	/** El convenio exige orden medica. La satisface una {@code OrdenMedica} vigente ese dia. */
	ORDEN,

	/**
	 * El convenio exige autorizacion previa. La satisface una {@code Autorizacion} APROBADA,
	 * vigente ese dia y con saldo.
	 */
	AUTORIZACION,

	/**
	 * El convenio exige credencial. La satisface el numero de afiliado de la cobertura, no vencido.
	 *
	 * <p>Es el unico de los tres que no necesita ninguna fila nueva: el dato ya vive en
	 * {@code cobertura_paciente} desde 03.04, con su fecha de vencimiento.
	 */
	CREDENCIAL
}
