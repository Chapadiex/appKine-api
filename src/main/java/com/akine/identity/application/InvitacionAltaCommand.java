package com.akine.identity.application;

/**
 * Datos con los que un administrador emite una invitacion (RF-M05-001).
 *
 * @param email         direccion tipeada, tal cual la escribio el administrador
 * @param roleCode      rol propuesto, de la matriz de permisos
 * @param consultorioId sede del vinculo propuesto, o {@code null} para alcance ORGANIZACION
 */
public record InvitacionAltaCommand(String email, String roleCode, Long consultorioId) {
}
