package com.akine.organization.spi;

/**
 * Por que se rechazo una autorizacion.
 *
 * <p><b>Existe para que el mapeo a HTTP ocurra en UN solo lugar.</b> Las tres reglas de codigos
 * de esta arquitectura —cross-tenant es 404, falta de contexto es 403 y nunca 401, sin permiso
 * sobre lo propio es 403— son faciles de enunciar y se pierden en cuanto cada llamador decide
 * por su cuenta. Con este enum, quien evalua dice QUE paso y un unico advice traduce a codigo.
 */
public enum DenialKind {

	/** No hubo rechazo. */
	NONE,

	/**
	 * El request no trae contexto de tenant y la operacion lo exige.
	 *
	 * <p>Se mapea a <b>403 {@code missing-tenant-context}</b>, nunca a 401: el interceptor del
	 * frontend borra el token ante cualquier 401 y el usuario entra en un bucle de login del
	 * que no sale. Y ademas es incorrecto: sabemos perfectamente quien es, lo que falta es que
	 * elija donde trabaja.
	 */
	NO_CONTEXT,

	/**
	 * El recurso pedido esta fuera del alcance del actor: otro tenant, u otra sede.
	 *
	 * <p>Se mapea a <b>404</b>, jamas a 403. Un 403 confirmaria que el recurso existe, y
	 * bastaria con probar ids consecutivos para enumerar los clientes del SaaS.
	 */
	OUT_OF_SCOPE,

	/**
	 * El recurso esta dentro del alcance del actor y le falta el permiso.
	 *
	 * <p>Se mapea a <b>403</b>. Aca el 404 no protege nada —el actor ya sabe que el recurso
	 * existe, es de su organizacion— y ademas le miente.
	 */
	NO_PERMISSION,

	/**
	 * La accion exige habilitacion profesional vigente a la fecha del evento y no la hay.
	 *
	 * <p><b>En Fase 1 este valor nunca se produce y siempre denegaria.</b> Se declara ahora para
	 * que el enum no cambie de forma cuando llegue F4 con {@code offering} (M27) —no para
	 * simular una funcionalidad que no existe—. Los permisos "segun rol clinico" de la matriz
	 * deniegan hoy, y que denieguen esta probado: documenta que faltan por diseño y no por
	 * descuido.
	 */
	CLINICAL_ENABLEMENT_MISSING
}
