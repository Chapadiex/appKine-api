package com.akine.identity.domain.port;

import java.util.Map;

/**
 * Encolado transaccional de los correos que dispara {@code identity}.
 *
 * <p>La fila se escribe <b>en la misma transaccion</b> que el negocio que la origina: si el
 * alta commitea, la notificacion existe; si el alta se revierte, no queda un correo
 * prometiendo una cuenta que no se creo. El envio, en cambio, es posterior al commit y su
 * fallo NO revierte el negocio (RN-M26-001).
 *
 * <h2>El token jamas viaja en el payload (T-11 / RN-M02-003)</h2>
 *
 * <p>La tentacion es poner {@code {"token": "..."}} en el JSON del outbox y que la plantilla
 * arme el enlace. Esta prohibido, y no por purismo: el outbox se consulta para diagnosticar
 * entregas fallidas, se reintenta desde una pantalla administrativa y termina en los backups.
 * Un token de activacion ahi es una credencial persistida en una tabla operativa, que es
 * exactamente lo que RN-M02-003 prohibe — y ademas es una credencial que sirve para tomar
 * una cuenta, no solo para leer un dato.
 *
 * <p>Por eso el contrato separa dos cosas que un solo mapa mezclaria:
 * <ul>
 *   <li>{@link Notificacion#datosPlantilla()} — lo que la plantilla renderiza y lo que un
 *       administrador puede mirar sin riesgo: nombre, organizacion. <b>Nunca secretos.</b></li>
 *   <li>{@link Notificacion#enlaceSeguro()} — el enlace YA CONSTRUIDO, que el worker
 *       solamente transporta: no lo indexa, no lo muestra en el listado administrativo y lo
 *       borra al terminar el envio. {@code null} cuando la notificacion no lleva enlace.</li>
 * </ul>
 *
 * <p>La alternativa —guardar el {@code tokenVerificacionId} y que el adaptador de correo le
 * pida el enlace a {@code identity} por {@code spi} al enviar— es mas pura pero obliga a
 * {@code notification} a conocer a {@code identity}. Se eligio construir el enlace aca: menos
 * acoplamiento, y la ventana en reposo se acota al tiempo de envio.
 */
public interface NotificationOutboxPort {

	/**
	 * Inserta la fila. Idempotente por {@link Notificacion#claveIdempotente()}: reintentar la
	 * transaccion del productor no puede producir dos correos.
	 */
	void encolar(Notificacion notificacion);

	/**
	 * Un correo pendiente de envio.
	 *
	 * @param organizationId  tenant al que pertenece, o {@code null} para un evento de
	 *                        identidad global —un reset pedido sin contexto no tiene tenant—
	 * @param tipo            que correo es
	 * @param destinatario    direccion de correo
	 * @param datosPlantilla  datos para renderizar. <b>Jamas tokens ni contrasenas</b>
	 * @param enlaceSeguro    enlace ya armado, o {@code null}. Campo de transporte: no se
	 *                        indexa, no se lista y se borra al cerrar el envio
	 * @param claveIdempotente clave unica del correo, p.ej. {@code "activacion:{tokenId}"}
	 */
	record Notificacion(
			Long organizationId,
			TipoNotificacion tipo,
			String destinatario,
			Map<String, String> datosPlantilla,
			String enlaceSeguro,
			String claveIdempotente) {

		public Notificacion {
			if (destinatario == null || destinatario.isBlank()) {
				throw new IllegalArgumentException("La notificacion necesita un destinatario");
			}
			if (claveIdempotente == null || claveIdempotente.isBlank()) {
				throw new IllegalArgumentException("La notificacion necesita una clave idempotente");
			}
			datosPlantilla = datosPlantilla == null ? Map.of() : Map.copyOf(datosPlantilla);
		}
	}

	/** Correos que {@code identity} origina en 01.02. */
	enum TipoNotificacion {

		/** Alta self-service: lleva el enlace de activacion. */
		ACTIVACION_CUENTA,

		/**
		 * Alguien pidio registrarse con una direccion que YA tiene cuenta.
		 *
		 * <p>Existe para que el registro pueda responder lo mismo exista o no la cuenta. Sin
		 * este correo, la unica forma de que el usuario legitimo entienda que paso seria que
		 * el endpoint le dijera "ese email ya esta registrado", y eso convierte el registro
		 * —el endpoint mas facil de automatizar de todos— en un verificador de direcciones.
		 * Con el, el usuario legitimo llega a destino y quien sondea no aprende nada.
		 *
		 * <p>No lleva enlace de credencial: la cuenta ya existe y ya tiene la suya.
		 */
		CUENTA_YA_REGISTRADA,

		/** Restablecimiento de contrasena: lleva el enlace de reset. */
		RESET_PASSWORD
	}
}
