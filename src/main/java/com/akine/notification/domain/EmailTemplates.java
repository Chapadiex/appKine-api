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
			case INVITACION_COLABORADOR -> "si".equals(payload.get("altaDeCentro"))
					? new EmailMessage(
							destinatario,
							"Tu centro " + payload.getOrDefault("organizacionNombre", "")
									+ " ya esta en AKINE",
							saludo + ",\n\nTe damos la bienvenida a AKINE. Ya creamos "
									+ payload.getOrDefault("organizacionNombre", "tu centro")
									+ " y vas a ser quien lo administre.\n\n"
									+ "Para crear tu cuenta entra en este enlace:\n" + enlace
									+ "\n\nDespues inicia sesion y te vamos a guiar para "
									+ "configurar tu sede.\n\nEl enlace se usa una sola vez."
									+ FIRMA)
					: new EmailMessage(
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
			// AKINE-08.02. Los dos avisos de clase dicen fecha, hora y sede, y NADA MAS: ni quien
			// mas esta anotado, ni cuantos lugares quedan, ni un id interno. CA-M26-006-06 pide
			// notificar sin exponer la lista de participantes. Desde U-46 (d) (17/10/2026)
			// tampoco el titulo de la clase: es texto libre y puede nombrar una condicion de salud.
			case CLASE_MODIFICADA -> new EmailMessage(
					destinatario,
					"Cambio tu clase en " + payload.getOrDefault("consultorioNombre", "AKINE"),
					saludo + ",\n\nHubo un cambio en tu clase del "
							+ payload.getOrDefault("claseInicio", "horario reservado")
							+ sede(payload)
							+ ".\n\nEntra en tu cuenta para ver el detalle, o comunicate con el "
							+ "centro si necesitas reprogramar." + FIRMA);
			case CUPO_LIBERADO -> new EmailMessage(
					destinatario,
					"Se libero tu lugar en la clase",
					saludo + ",\n\nSe libero un lugar y ya estas inscripto en la clase del "
							+ payload.getOrDefault("claseInicio", "horario reservado")
							+ sede(payload)
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
							+ sede(payload) + "." + comoLlegar(payload)
							+ "\n\nSi no podes asistir, avisale al centro con anticipacion para "
							+ "liberar el lugar." + FIRMA);
			case TURNO_CANCELADO -> new EmailMessage(
					destinatario,
					"Se cancelo tu turno en " + payload.getOrDefault("consultorioNombre", "AKINE"),
					saludo + ",\n\nSe cancelo tu turno" + servicio(payload)
							+ " del " + payload.getOrDefault("turnoInicio", "horario reservado")
							+ sede(payload) + "." + comoLlegar(payload)
							+ "\n\nSi queres sacar uno nuevo, comunicate con el centro." + FIRMA);
			case TURNO_REPROGRAMADO -> turnoModificado(destinatario, saludo, payload);
			// AKINE-08.08 (RF-M26-008). Producto, fecha y saldo. CA-M26-008-06: el aviso no renueva
			// ni consume nada, asi que el texto no ofrece ninguna accion que lo haga.
			case PASE_POR_VENCER -> new EmailMessage(
					destinatario,
					"Tu " + payload.getOrDefault("productoNombre", "pase") + " vence pronto",
					saludo + ",\n\nTu " + payload.getOrDefault("productoNombre", "pase")
							+ sede(payload) + " vale hasta el "
							+ payload.getOrDefault("paseVencimiento", "dia de su vencimiento")
							+ creditos(payload) + "."
							+ ("si".equals(payload.getOrDefault("renovacionAutomatica", null))
									? "\n\nSe va a renovar automaticamente por el periodo siguiente."
									: "\n\nSi queres seguir, comunicate con el centro para renovarlo.")
							+ FIRMA);
			case PASE_SALDO_BAJO -> new EmailMessage(
					destinatario,
					"Te quedan pocas clases en tu " + payload.getOrDefault("productoNombre", "pase"),
					saludo + ",\n\nTe quedan " + payload.getOrDefault("creditosDisponibles", "pocas")
							+ " clase(s) en tu " + payload.getOrDefault("productoNombre", "pase")
							+ sede(payload) + ", que vale hasta el "
							+ payload.getOrDefault("paseVencimiento", "dia de su vencimiento")
							+ ".\n\nSi queres seguir despues, comunicate con el centro." + FIRMA);
			// DP-40 (U-43). Al administrador: cuantos abonos, en que sede y quien habia autorizado la
			// renovacion. Ningun dato de las personas titulares: para verlas, la aplicacion.
			case PEDIDO_DE_PRUEBA_RECIBIDO -> new EmailMessage(
					destinatario,
					"Nuevo pedido de prueba: " + payload.getOrDefault("organizacionNombre", "un centro"),
					saludo + ",\n\n" + payload.getOrDefault("organizacionNombre", "Un centro")
							+ " pidio la prueba gratis de 30 dias desde la pagina.\n\nServicios: "
							+ payload.getOrDefault("pedidoServicios", "sin indicar")
							+ "\nSedes: " + payload.getOrDefault("pedidoSedes", "sin indicar")
							+ "\n\nLos datos de contacto estan en la consola de plataforma, en Pedidos de "
							+ "prueba. Desde ahi lo das de alta o lo descartas." + FIRMA);
			case PRUEBA_POR_VENCER -> new EmailMessage(
					destinatario,
					"Tu prueba de AKINE termina el " + payload.getOrDefault("pruebaVencimiento", "pronto"),
					saludo + ",\n\nLa prueba gratis de " + payload.getOrDefault("organizacionNombre", "tu centro")
							+ " termina el " + payload.getOrDefault("pruebaVencimiento", "dia de su vencimiento")
							+ dias(payload)
							+ ".\n\nPara seguir usando AKINE sin cortes, elegi tu plan respondiendo este correo. "
							+ "Si no, el centro queda en solo lectura: vas a poder ver y exportar todo, "
							+ "y no se borra nada." + FIRMA);
			case PRUEBA_VENCIDA -> new EmailMessage(
					destinatario,
					"Termino tu prueba de AKINE",
					saludo + ",\n\nTermino la prueba gratis de "
							+ payload.getOrDefault("organizacionNombre", "tu centro")
							+ ". El centro quedo en solo lectura: podes ver y exportar todo, pero no cargar "
							+ "nada nuevo. No se borro nada.\n\nPara seguir, elegi tu plan respondiendo "
							+ "este correo." + FIRMA);
			case SUSCRIPCION_POR_VENCER -> new EmailMessage(
					destinatario,
					"Tu suscripcion de AKINE vence el " + payload.getOrDefault("suscripcionVencimiento", "pronto"),
					saludo + ",\n\nLa suscripcion de " + payload.getOrDefault("organizacionNombre", "tu centro")
							+ " vence el " + payload.getOrDefault("suscripcionVencimiento", "dia de su vencimiento")
							+ dias(payload)
							+ ".\n\nPara seguir usando AKINE sin cortes, renova el pago antes de esa fecha. "
							+ "Si ya pagaste, ignora este correo." + FIRMA);
			case SUSCRIPCION_VENCIDA -> new EmailMessage(
					destinatario,
					"Vencio tu suscripcion de AKINE",
					saludo + ",\n\nVencio la suscripcion de "
							+ payload.getOrDefault("organizacionNombre", "tu centro")
							+ ". Tenes 3 dias de gracia: el centro sigue funcionando normalmente hasta el "
							+ payload.getOrDefault("suscripcionSoloLectura", "fin de la gracia")
							+ ". Despues queda en solo lectura: vas a poder ver todo, pero no cargar nada nuevo."
							+ "\n\nPara evitarlo, renova el pago. Si ya pagaste, ignora este correo." + FIRMA);
			case SUSCRIPCION_SUSPENDIDA -> new EmailMessage(
					destinatario,
					"Tu centro quedo en solo lectura",
					saludo + ",\n\nTermino la gracia de la suscripcion de "
							+ payload.getOrDefault("organizacionNombre", "tu centro")
							+ " y el centro quedo en solo lectura: podes ver todo, pero no cargar nada nuevo."
							+ "\n\nSi no se renueva el pago, el " + payload.getOrDefault("suscripcionCierre", "dia del cierre")
							+ " se cierra el acceso al centro. No se borra nada." + FIRMA);
			case SUSCRIPCION_CERRADA -> new EmailMessage(
					destinatario,
					"Se cerro el acceso a tu centro en AKINE",
					saludo + ",\n\nSe cerro el acceso a " + payload.getOrDefault("organizacionNombre", "tu centro")
							+ " por falta de pago. No se borro nada: los datos se conservan."
							+ "\n\nPara volver a entrar, renova el pago respondiendo este correo y el acceso "
							+ "se habilita en el momento." + FIRMA);
case ABONO_RENOVACION_APAGADA -> new EmailMessage(
					destinatario,
					"Se apago la renovacion automatica de abonos"
							+ (payload.getOrDefault("consultorioNombre", null) == null ? ""
									: " en " + payload.getOrDefault("consultorioNombre", null)),
					saludo + ",\n\nSe apago la renovacion automatica de "
							+ payload.getOrDefault("abonosAfectados", "uno o mas") + " abono(s)"
							+ sede(payload) + autorizante(payload)
							+ ", porque esa persona ya no puede vender en la sede. Los periodos ya "
							+ "vendidos no cambian; el siguiente no se va a vender solo."
							+ "\n\nSi tienen que seguir renovandose, alguien que pueda vender tiene que "
							+ "volver a activar la renovacion en cada abono." + FIRMA);
		};
	}

	/**
	 * "Tu turno cambio": el unico aviso de una reprogramacion y de un cambio de sede. Lista lo que
	 * cambio de antes a ahora; lo que no cambio no aparece. Solo el horario (o una fila vieja del
	 * outbox, sin las marcas nuevas) conserva el texto de siempre.
	 */
	private static EmailMessage turnoModificado(String destinatario, String saludo, SanitizedPayload payload) {
		String sedeAnterior = payload.getOrDefault("consultorioNombreAnterior", null);
		String inicioAnterior = payload.getOrDefault("turnoInicioAnterior", null);
		boolean otroProfesional = "si".equals(payload.getOrDefault("cambioProfesional", null));
		boolean otroBox = "si".equals(payload.getOrDefault("cambioEspacio", null));
		String inicio = payload.getOrDefault("turnoInicio", "nuevo horario acordado");

		if (sedeAnterior == null && !otroProfesional && !otroBox) {
			return new EmailMessage(
					destinatario,
					"Cambio el horario de tu turno en " + payload.getOrDefault("consultorioNombre", "AKINE"),
					saludo + ",\n\nTu turno" + servicio(payload) + anterior(payload)
							+ " paso al " + inicio + sede(payload) + "." + comoLlegar(payload)
							+ "\n\nSi el nuevo horario no te queda bien, comunicate con el centro." + FIRMA);
		}

		StringBuilder cambios = new StringBuilder();
		if (sedeAnterior != null) {
			cambios.append("\n- Sede: antes ").append(sedeAnterior).append(", ahora ")
					.append(payload.getOrDefault("consultorioNombre", "otra sede"));
		}
		if (inicioAnterior != null) {
			cambios.append("\n- Fecha y hora: antes ").append(inicioAnterior).append(", ahora ").append(inicio);
		}
		if (otroProfesional) {
			cambios.append("\n- Profesional: te va a atender otro profesional");
		}
		if (otroBox) {
			cambios.append("\n- Box: te van a atender en otro box de la sede");
		}
		return new EmailMessage(
				destinatario,
				"Cambio tu turno en " + payload.getOrDefault("consultorioNombre", "AKINE"),
				saludo + ",\n\nHubo cambios en tu turno" + servicio(payload) + " del "
						+ (inicioAnterior != null ? inicioAnterior : inicio) + ":\n" + cambios
						+ "\n\nTu turno queda para el " + inicio + sede(payload) + "." + comoLlegar(payload)
						+ "\n\nSi el cambio no te queda bien, comunicate con el centro." + FIRMA);
	}

	/**
	 * La direccion de la sede y, si hay coordenadas, el enlace para llegar (V140). Vacio si la sede
	 * no publica su direccion: {@code AvisosDeTurno} no manda las claves y aca no se inventa nada.
	 *
	 * <p>El enlace se arma ACA y no viaja en el payload: una URL en el payload la rechaza la lista de
	 * fragmentos prohibidos del outbox, y esa regla sigue valiendo. Lo unico que llega son dos numeros.
	 */
	private static String comoLlegar(SanitizedPayload payload) {
		String direccion = payload.getOrDefault("consultorioDireccion", null);
		if (direccion == null) {
			return "";
		}
		StringBuilder texto = new StringBuilder("\nDireccion: ").append(direccion);
		String latitud = payload.getOrDefault("consultorioLatitud", null);
		String longitud = payload.getOrDefault("consultorioLongitud", null);
		if (latitud != null && longitud != null) {
			texto.append("\nComo llegar: https://www.google.com/maps/dir/?api=1&destination=")
					.append(latitud).append(',').append(longitud);
		}
		return texto.toString();
	}

	private static String autorizante(SanitizedPayload payload) {
		String quien = payload.getOrDefault("renovacionAutorizadaPor", null);
		return quien == null ? "" : " que habia autorizado " + quien;
	}

	private static String creditos(SanitizedPayload payload) {
		String creditos = payload.getOrDefault("creditosDisponibles", null);
		return creditos == null ? "" : ", con " + creditos + " clase(s) disponible(s)";
	}

	private static String servicio(SanitizedPayload payload) {
		String servicio = payload.getOrDefault("servicioNombre", null);
		return servicio == null ? "" : " de " + servicio;
	}

	/** " (falta 1 dia)" o " (faltan N dias)"; nada sin el dato (filas viejas del outbox). */
	private static String dias(SanitizedPayload payload) {
		String dias = payload.getOrDefault("diasRestantes", null);
		if (dias == null) {
			return "";
		}
		return "1".equals(dias) ? " (falta 1 dia)" : " (faltan " + dias + " dias)";
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
