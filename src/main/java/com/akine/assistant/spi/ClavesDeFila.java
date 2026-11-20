package com.akine.assistant.spi;

/**
 * Las claves de las filas de {@link ResultadoDeHerramienta}. Son el contrato entre la herramienta de
 * cada modulo y la plantilla del asistente: una herramienta que escribe otra clave no rompe nada,
 * pero su dato no aparece en el texto.
 *
 * <p>Los instantes viajan como {@link java.time.Instant}; la plantilla los muestra en la zona de la
 * sede. Los estados viajan con el nombre del modulo dueno ({@code ATENDIDO}, {@code PRESENTE}...).
 */
public final class ClavesDeFila {

	// --- COMO_SE_HACE: un pasaje del manual, citado textual ---
	public static final String MANUAL_FICHA = "manualFicha";
	public static final String MANUAL_TITULO = "manualTitulo";
	public static final String MANUAL_TEXTO = "manualTexto";
	public static final String MANUAL_ENLACE = "manualEnlace";

	// --- BUSCAR_PERSONA ---
	public static final String PERSONA_ID = "personaId";
	public static final String NOMBRE = "nombre";
	/** Ya enmascarado por la herramienta: nunca el numero entero. */
	public static final String DOCUMENTO = "documento";

	// --- Turnos (ASISTENCIA_PERSONA, SESION_DE_PERSONA, AGENDA_DEL_DIA) ---
	public static final String TURNO_ID = "turnoId";
	public static final String INICIO = "inicio";
	/** El estado operativo del turno: RESERVADO, CONFIRMADO, PRESENTE, EN_ATENCION, ATENDIDO, AUSENTE, RETIRADO, CANCELADO. */
	public static final String ESTADO = "estado";
	public static final String OFERTA = "oferta";
	public static final String PROFESIONAL = "profesional";
	public static final String PACIENTE = "paciente";
	public static final String SESION_ID = "sesionId";

	// --- ASISTENTES_CLASE ---
	public static final String CLASE_ID = "claseId";
	public static final String CLASE = "clase";
	public static final String ESTADO_CLASE = "estadoClase";
	public static final String PARTICIPANTE = "participante";
	/** PRESENTE, PRESENTE_TARDE, AUSENTE, o null si todavia no se registro. */
	public static final String RESULTADO = "resultado";

	// --- REPORTE ---
	public static final String SECCION = "seccion";
	public static final String ETIQUETA = "etiqueta";
	/** DINERO, CONTEO o PORCENTAJE. */
	public static final String TIPO = "tipo";
	public static final String VALOR = "valor";
	public static final String MONEDA = "moneda";

	private ClavesDeFila() {
	}
}
