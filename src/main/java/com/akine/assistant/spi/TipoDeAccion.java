package com.akine.assistant.spi;

/**
 * Lo que el asistente sabe proponer y, con la confirmacion de la persona, grabar
 * (docs/diseno/AKINE-asistente-acciones.md). Una {@link AccionDelAsistente} por tipo.
 */
public enum TipoDeAccion {
	/** Presentes y ausentes de una clase de hoy. */
	ASISTENCIA_CLASE,
	/** Un cobro a un paciente, imputado a sus deudas. */
	PAGO,
	/** La sesion de hoy de un paciente con sus tratamientos. Solo quien tiene {@code sesion:register}. */
	ATENCION,
	/** La llegada del paciente a su turno de hoy: lo que obtiene quien no puede registrar la atencion. */
	LLEGADA,
	/** Una persona nueva con su perfil de paciente. */
	PACIENTE_ALTA,
	/** Un cambio en los datos de contacto o de identidad de una persona. */
	PACIENTE_EDICION
}
