package com.akine.assistant.application;

/**
 * Los textos fijos que todavia usan las acciones del asistente. Las respuestas a consultas las redacta
 * el agente a partir de los resultados de las herramientas (docs/diseno/AKINE-asistente-agente.md §5.4).
 */
final class Plantillas {

	static final String AYUDA = "Todavía no sé responder eso. Puedo decirte si una persona vino o tiene "
			+ "la sesión cargada, quiénes vienen un día, quiénes fueron a una clase, y los números de "
			+ "facturación, turnos u ocupación de clases de un período.";
	static final String FALTA_PERSONA = "¿De qué persona? Escribí el nombre.";

	private Plantillas() {
	}

	static String personaNoEncontrada(String texto) {
		return "No encontré a \"" + texto + "\" en el padrón.";
	}

	static String variasPersonas(String texto) {
		return "Encontré varias personas con \"" + texto + "\". ¿Cuál?";
	}
}
