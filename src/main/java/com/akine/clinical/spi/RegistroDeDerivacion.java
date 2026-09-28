package com.akine.clinical.spi;

/**
 * El pedido de vincular una participacion grupal a un contexto clinico (RF-M28-008, RF-M10-008).
 *
 * <p><b>El Caso no se crea: se elige.</b> {@code casoClinicoId} tiene que existir, estar activo y
 * colgar de la historia de esa persona. Abrir un Caso pide diagnostico presuntivo, objetivo
 * terapeutico y equipo —contenido clinico—, y meter eso en el cuerpo de un comando que entra por
 * una ruta de clases seria copiar PHI al modulo grupal, que es lo que el plan prohibe en su seccion
 * de base de datos. La pantalla abre el Caso con el endpoint de M10 que existe desde 04.03 y
 * despues deriva.
 *
 * @param planTratamientoId {@code null} legitimo (RF-M11-007): un Caso sin plan activo se deriva
 *                          igual. Si viene, se valida que pertenezca a {@code casoClinicoId}
 * @param autorizacionId    {@code null} legitimo (RF-M11-008): el alcance vigente es el Circuito
 *                          Particular y un paciente particular no tiene ninguna. Si viene, tiene
 *                          que habilitar contra {@code participacion.fechaLocal}. <b>No se
 *                          consume</b>: consumir es de {@code person} y lo dispara el cierre de una
 *                          Sesion, que es 08.05
 */
public record RegistroDeDerivacion(
		ActorDeDerivacion actor,
		ParticipacionDerivable participacion,
		long casoClinicoId,
		Long planTratamientoId,
		Long autorizacionId,
		String motivo) {
}
