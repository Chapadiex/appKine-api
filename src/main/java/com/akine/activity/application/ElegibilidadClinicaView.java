package com.akine.activity.application;

import java.util.List;

/**
 * El veredicto GRUPAL de si un participante se puede derivar, sin efectos y sin leer nada clinico
 * (RF-M28-008, "explicacion de bloqueos").
 *
 * <p>Responde lo que {@code activity} sabe: si la oferta es clinica, si la persona estuvo y si ya
 * es paciente. Lo clinico —Caso activo, Plan, autorizacion, permiso clinico— lo decide
 * {@code clinical} al derivar, y no se anticipa aca: anticiparlo exigiria una lectura clinica
 * auditada por cada fila de la lista de la clase.
 *
 * @param motivos codigos de bloqueo, vacio si es derivable: {@code OFERTA_NO_CLINICA},
 *                {@code SIN_ASISTENCIA}, {@code AUSENTE}
 * @param requiereActivarPerfil la persona no es paciente: derivarla exige
 *                              {@code activarPerfilPaciente = true}. No bloquea por si solo
 */
public record ElegibilidadClinicaView(
		long inscripcionId,
		long personaId,
		Long asistenciaId,
		boolean ofertaClinica,
		boolean requiereCasoClinico,
		String resultadoAsistencia,
		boolean esPacienteVigente,
		boolean requiereActivarPerfil,
		boolean derivable,
		List<String> motivos) {
}
