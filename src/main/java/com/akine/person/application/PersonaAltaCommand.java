package com.akine.person.application;

import com.akine.person.domain.TipoDocumento;

import java.time.LocalDate;

/**
 * Alta de una Persona (RF-M07-002, RF-M07-007).
 *
 * <p><b>Es un alta de PERSONA y nunca de paciente</b>, y no lleva ninguna bandera para pedir las
 * dos cosas de una vez. RF-M07-007 pide poder registrar a alguien que viene a una actividad no
 * clinica sin crearle nada clinico, y RF-M07-010 pide que ese camino no produzca artefactos
 * clinicos por accidente. Un {@code boolean esPaciente} en este comando seria exactamente la
 * puerta por la que eso pasa: dos pantallas despues, alguien lo manda en {@code true} "porque
 * total suele ser paciente". Activar el perfil es una operacion aparte, con su ruta, su permiso y
 * su evento de auditoria — ver {@code PerfilPacienteService}.
 *
 * <p><b>Sin {@code Idempotency-Key}</b>, mismo criterio que el alta de servicio y de oferta: la
 * proteccion contra el doble click la da el unique de documento, que es la unica sin ventana de
 * carrera. Para una persona SIN documento no hay proteccion posible ni deseable — dos hermanos
 * homonimos sin DNI son dos personas legitimas—, y ahi lo que actua es la deteccion de
 * coincidencias, que le pregunta al operador en vez de decidir sola.
 *
 * @param confirmaPosibleDuplicado el operador ya vio las coincidencias y declara que es otra
 *                                 persona. Es la traduccion por API de "hice la busqueda previa"
 *                                 que RN-M07-001 exige; ver
 *                                 {@code PersonaPosibleDuplicadoException}. <b>No saltea el
 *                                 documento repetido</b>, que es un invariante duro y no una
 *                                 advertencia
 */
public record PersonaAltaCommand(
		TipoDocumento tipoDocumento,
		String numeroDocumento,
		String apellido,
		String nombre,
		LocalDate fechaNacimiento,
		String email,
		String telefono,
		String notas,
		boolean confirmaPosibleDuplicado) {
}
