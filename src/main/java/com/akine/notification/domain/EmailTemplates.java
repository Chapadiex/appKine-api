package com.akine.notification.domain;

import com.akine.notification.spi.NotificationType;

/**
 * Redaccion de los mails de 01.02. Castellano, texto plano, sin HTML (RF-M26-001).
 *
 * <p>Clase PURA. El enlace entra por parametro y sale dentro del {@link EmailMessage}: nunca
 * se guarda, nunca se loguea (T-11).
 *
 * <p>Los textos evitan afirmar nada que un tercero no deberia poder confirmar. El mail de
 * "ya tenes cuenta" (challenge D-4) existe justamente para que el registro pueda responder
 * 202 uniforme sin revelar si el email estaba registrado: quien lo recibe ya sabe que tiene
 * cuenta, y quien no la tiene no recibe nada.
 */
public final class EmailTemplates {

	private static final String FIRMA = "\n\nEl equipo de AKINE";
	private static final String SIN_NOMBRE = "Hola";

	private EmailTemplates() {
		// Clase de utilidad.
	}

	/**
	 * Redacta el mensaje.
	 *
	 * @param enlace enlace seguro ya reconstruido, o {@code null} para los tipos que no llevan
	 * @throws IllegalArgumentException si el tipo exige enlace y no se paso ninguno
	 */
	public static EmailMessage render(
			NotificationType tipo, String destinatario, SanitizedPayload payload, String enlace) {
		if (tipo.requiereEnlaceSeguro() && (enlace == null || enlace.isBlank())) {
			throw new IllegalArgumentException(
					"El tipo " + tipo + " no puede redactarse sin un enlace seguro");
		}
		String saludo = saludo(payload);
		return switch (tipo) {
			case ACTIVACION_CUENTA -> new EmailMessage(
					destinatario,
					"Activa tu cuenta de AKINE",
					saludo + ",\n\nPara activar tu cuenta entra en este enlace:\n" + enlace
							+ "\n\nEl enlace se usa una sola vez. Si no pediste esta cuenta, "
							+ "ignora este mensaje." + FIRMA);
			case INVITACION_COLABORADOR -> new EmailMessage(
					destinatario,
					"Te invitaron a trabajar en " + payload.getOrDefault("organizacionNombre", "AKINE"),
					saludo + ",\n\n"
							+ payload.getOrDefault("invitadoPor", "Un administrador")
							+ " te invito a " + payload.getOrDefault("organizacionNombre", "su organizacion")
							+ " en AKINE.\n\nPara aceptar la invitacion entra en este enlace:\n" + enlace
							+ "\n\nEl enlace se usa una sola vez." + FIRMA);
			case RECUPERACION_PASSWORD -> new EmailMessage(
					destinatario,
					"Restablece tu contrasena de AKINE",
					saludo + ",\n\nRecibimos un pedido para restablecer tu contrasena. "
							+ "Entra en este enlace:\n" + enlace
							+ "\n\nEl enlace se usa una sola vez y vence pronto. Si no lo pediste, "
							+ "no hace falta que hagas nada: tu contrasena sigue igual." + FIRMA);
			case CUENTA_YA_REGISTRADA -> new EmailMessage(
					destinatario,
					"Ya tenes una cuenta en AKINE",
					saludo + ",\n\nAlguien intento registrar una cuenta con este email y ya "
							+ "tenes una. Inicia sesion normalmente o, si no recordas tu "
							+ "contrasena, usa la opcion de recuperarla." + FIRMA);
			// AKINE-08.02. Los dos avisos de clase nombran la clase y el horario, y NADA MAS: ni
			// quien mas esta anotado, ni cuantos lugares quedan, ni un id interno. CA-M26-006-06
			// pide notificar sin exponer la lista de participantes.
			case CLASE_MODIFICADA -> new EmailMessage(
					destinatario,
					"Cambio tu clase en " + payload.getOrDefault("consultorioNombre", "AKINE"),
					saludo + ",\n\nHubo un cambio en la clase "
							+ payload.getOrDefault("claseTitulo", "en la que estabas inscripto")
							+ " del " + payload.getOrDefault("claseInicio", "horario reservado")
							+ ".\n\nEntra en tu cuenta para ver el detalle, o comunicate con el "
							+ "centro si necesitas reprogramar." + FIRMA);
			case CUPO_LIBERADO -> new EmailMessage(
					destinatario,
					"Se libero tu lugar en la clase",
					saludo + ",\n\nSe libero un lugar y ya estas inscripto en la clase "
							+ payload.getOrDefault("claseTitulo", "en la que estabas esperando")
							+ " del " + payload.getOrDefault("claseInicio", "horario reservado")
							+ ".\n\nEl lugar ya es tuyo: no hace falta que confirmes nada. Si no "
							+ "podes asistir, avisale al centro para liberarlo." + FIRMA);
			// AKINE E-5 (RF-M26-002/003). Fecha, hora, sede y servicio, y NADA MAS: ni el
			// profesional, ni el motivo de una cancelacion —texto libre que puede ser clinico—, ni
			// un id interno. RN-M26-002.
			case TURNO_RESERVADO -> new EmailMessage(
					destinatario,
					"Tu turno en " + payload.getOrDefault("consultorioNombre", "AKINE"),
					saludo + ",\n\nTe reservamos un turno" + servicio(payload)
							+ " para el " + payload.getOrDefault("turnoInicio", "horario acordado")
							+ sede(payload)
							+ ".\n\nSi no podes asistir, avisale al centro con anticipacion para "
							+ "liberar el lugar." + FIRMA);
			case TURNO_CANCELADO -> new EmailMessage(
					destinatario,
					"Se cancelo tu turno en " + payload.getOrDefault("consultorioNombre", "AKINE"),
					saludo + ",\n\nSe cancelo tu turno" + servicio(payload)
							+ " del " + payload.getOrDefault("turnoInicio", "horario reservado")
							+ sede(payload)
							+ ".\n\nSi queres sacar uno nuevo, comunicate con el centro." + FIRMA);
			case TURNO_REPROGRAMADO -> new EmailMessage(
					destinatario,
					"Cambio el horario de tu turno en "
							+ payload.getOrDefault("consultorioNombre", "AKINE"),
					saludo + ",\n\nTu turno" + servicio(payload) + anterior(payload)
							+ " paso al " + payload.getOrDefault("turnoInicio", "nuevo horario acordado")
							+ sede(payload)
							+ ".\n\nSi el nuevo horario no te queda bien, comunicate con el centro."
							+ FIRMA);
		};
	}

	private static String servicio(SanitizedPayload payload) {
		String servicio = payload.getOrDefault("servicioNombre", null);
		return servicio == null ? "" : " de " + servicio;
	}

	private static String sede(SanitizedPayload payload) {
		String sede = payload.getOrDefault("consultorioNombre", null);
		return sede == null ? "" : " en " + sede;
	}

	private static String anterior(SanitizedPayload payload) {
		String anterior = payload.getOrDefault("turnoInicioAnterior", null);
		return anterior == null ? "" : " del " + anterior;
	}

	private static String saludo(SanitizedPayload payload) {
		String nombre = payload.getOrDefault("nombre", null);
		return nombre == null ? SIN_NOMBRE : "Hola " + nombre;
	}
}
