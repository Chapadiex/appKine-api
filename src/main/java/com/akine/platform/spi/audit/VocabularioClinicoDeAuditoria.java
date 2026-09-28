package com.akine.platform.spi.audit;

import java.util.List;

/**
 * Que eventos de {@code audit_event} son <b>clinicos</b>, y por lo tanto exigen
 * {@code auditoria:read-clinica} para leerse sin redactar.
 *
 * <h2>Por que hace falta separar</h2>
 *
 * <p>{@link AuditEntry} prohibe meter contenido clinico en la auditoria, y los modulos lo
 * respetan: el resumen de la historia no va en los detalles, el nombre del archivo adjunto
 * tampoco —"rmn-rodilla-rotura-menisco.pdf" es el diagnostico—. Pero hay un campo que si viaja,
 * porque DP-03 obliga a guardarlo: la <b>justificacion declarada</b> del acceso, que va en
 * {@code reason} y es texto libre que escribe un profesional. "El paciente llamo por el
 * resultado del estudio de rodilla" es contenido clinico en miniatura, y esta en cada una de las
 * ~30 filas que escribe el modulo clinico.
 *
 * <p>El lector de esa tabla evaluaba <b>un solo permiso</b>, {@code auditoria:read}, y no el
 * clinico. Un {@code ORG_ADMIN} lo tiene por asignacion base con alcance de organizacion y
 * <b>no</b> tiene {@code hc:read} —la matriz §2 le dice "No por defecto" en Ver Historia
 * Clinica—: por la pantalla de auditoria leia, de cada paciente de su organizacion, el
 * {@code personaId}, el {@code historiaClinicaId}, la via de acceso, la categoria del adjunto
 * descargado y el motivo en texto libre. {@code auditoria:read-clinica} existia en el catalogo,
 * era otorgable como grant y <b>no lo evaluaba ninguna linea del repositorio</b>; el javadoc de
 * {@code AuditEventRepository} decia que lo agregaba AKINE-01.03, y nunca se agrego. Cerrado en
 * AKINE-07.07.
 *
 * <h2>Por que la lista vive aca y no en el modulo clinico</h2>
 *
 * <p>Quien decide es {@code organization.application.AuditQueryService} —la matriz de permisos es
 * suya—, y {@code organization} no puede importar {@code clinical}: cerraria un ciclo y ArchUnit
 * lo rechaza. {@code platform.spi.audit} es el unico lugar que los dos ya conocen, y es donde
 * viven {@link AuditEntry} y {@link AuditEventSummary}, o sea el vocabulario mismo.
 *
 * <h2>La limitacion, dicha en voz alta</h2>
 *
 * <p>Esto es una <b>lista</b>, y una lista se olvida. Un evento clinico nuevo cuyo prefijo no
 * este aca se lee sin redactar y nada falla. No hay forma de hacerlo fallar cerrado sin marcar
 * el evento en origen, o sea sin agregarle un campo a {@link AuditEntry} y tocar a todos sus
 * emisores. Mientras tanto: <b>agregar un tipo de evento clinico obliga a agregar su prefijo
 * aca</b>, y el test de esta clase enumera el vocabulario conocido para que el olvido tenga al
 * menos un lugar donde verse.
 */
public final class VocabularioClinicoDeAuditoria {

	/**
	 * Prefijos de {@code eventType} cuyo {@code reason} y {@code details} son clinicos.
	 *
	 * <p>Se compara por prefijo y no por igualdad porque cada agregado emite entre tres y ocho
	 * verbos —{@code _OPENED}, {@code _UPDATED}, {@code _CLOSED}, {@code _ACCESSED}…— y
	 * enumerarlos uno por uno multiplica por seis las chances de que falte el que importa.
	 */
	private static final List<String> PREFIJOS_CLINICOS = List.of(
			// M09 — Historia Clinica (AKINE-04.01).
			"HISTORIA_CLINICA_",
			"ANTECEDENTE_CLINICO_",
			// M10 — timeline, entrada versionada y adjunto (AKINE-04.02).
			"TIMELINE_",
			"ENTRADA_CLINICA_",
			"ADJUNTO_CLINICO_",
			// M11 — Caso Clinico y su equipo (AKINE-04.03).
			"CASO_CLINICO_",
			"CASO_EQUIPO_",
			// M13 — Plan de Tratamiento (AKINE-04.04, AKINE-04.05).
			"PLAN_TRATAMIENTO_",
			"PLAN_ITEM_",
			// M14 — Sesion. La atencion es el hecho clinico por excelencia.
			"SESION_");

	private VocabularioClinicoDeAuditoria() {
		// Utilidad.
	}

	/**
	 * @param eventType el {@code eventType} de la fila, tal como lo escribio el modulo emisor
	 * @return {@code true} si leer su {@code reason} y sus {@code details} exige
	 *         {@code auditoria:read-clinica}
	 */
	public static boolean esClinico(String eventType) {
		if (eventType == null || eventType.isBlank()) {
			return false;
		}
		return PREFIJOS_CLINICOS.stream().anyMatch(eventType::startsWith);
	}
}
