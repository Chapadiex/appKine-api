package com.akine.offering.application;

import java.time.Instant;
import java.util.List;

/**
 * Como esta configurada una Oferta: quien puede prestarla, donde, y con que capacidad real.
 *
 * <h2>{@code restringida} es explicito y no se deduce de la lista</h2>
 *
 * <p>La ausencia de habilitaciones significa <b>sin restringir</b>, no "nadie". Deducirlo de
 * {@code profesionales.isEmpty()} obligaria a cada consumidor a conocer esa regla, y el dia que
 * uno la lea al reves va a mostrar "ningun profesional habilitado" sobre una oferta que cualquiera
 * puede prestar. Se publica como campo propio para que la pantalla lo diga con palabras.
 *
 * <p>Lo mismo por el otro lado: quien borra la ultima habilitacion creyendo que restringe, abre la
 * oferta a todos. Ese es el error que este campo existe para hacer visible.
 *
 * @param capacidadComercial la de la oferta, tal como la cargo el centro
 * @param capacidadEfectiva  {@code min(comercial, capacidad de los espacios habilitados)}
 * @param espacioQueLimita   nombre del espacio que fija la efectiva, o {@code null} si no la acota
 *                           ninguno. Un numero mas chico sin explicacion es un bug reportado
 * @param ofertaVersion      version de la OFERTA que el cliente tiene que mandar como
 *                           {@code expectedVersion} en el proximo reemplazo. En una lectura es la
 *                           vigente. En la respuesta de un reemplazo es la que la oferta <b>va a
 *                           tener despues del commit</b> ({@code leida + 1}), no la leida: el
 *                           {@code OPTIMISTIC_FORCE_INCREMENT} la sube al cerrar la transaccion,
 *                           cuando esta vista ya se armo. Devolver la leida haria que encadenar dos
 *                           reemplazos sin releer diera 409 siempre
 */
public record HabilitacionesView(
		long ofertaId,
		long ofertaVersion,
		boolean restringidaPorProfesional,
		boolean restringidaPorEspacio,
		List<ProfesionalHabilitadoView> profesionales,
		List<EspacioHabilitadoView> espacios,
		int capacidadComercial,
		int capacidadEfectiva,
		String espacioQueLimita) {

	/**
	 * Un profesional habilitado.
	 *
	 * @param vigenteHoy si la habilitacion rige AHORA. Distinto de {@code estado}: una
	 *                   habilitacion activa que arranca el mes que viene tiene {@code ACTIVO} y
	 *                   {@code vigenteHoy=false}
	 * @param vinculoVigente si la membership sigue habilitando a esa persona en la sede. Puede ser
	 *                   {@code false} con la habilitacion intacta: el colaborador se desvinculo y
	 *                   la fila quedo colgando. No se esconde, se muestra con su motivo
	 */
	public record ProfesionalHabilitadoView(
			long id,
			long membershipId,
			String nombre,
			String roleCode,
			Instant validFrom,
			Instant validUntil,
			String estado,
			boolean vigenteHoy,
			boolean vinculoVigente,
			Instant deletedAt,
			String deactivationReason,
			long version) {
	}

	/**
	 * Un espacio habilitado.
	 *
	 * @param enServicio si el espacio sigue operativo en {@code resource}. Puede ser {@code false}
	 *                   con la habilitacion intacta: el espacio se dio de baja en su propio modulo
	 *                   y esta fila quedo apuntandolo. Se muestra tachada con su motivo, no se
	 *                   esconde — esconderla dejaria al administrador sin entender por que la
	 *                   capacidad efectiva cambio sola
	 */
	public record EspacioHabilitadoView(
			long id,
			long espacioId,
			String nombre,
			String tipo,
			int capacidad,
			Instant validFrom,
			Instant validUntil,
			String estado,
			boolean vigenteHoy,
			boolean enServicio,
			Instant deletedAt,
			String deactivationReason,
			long version) {
	}
}
