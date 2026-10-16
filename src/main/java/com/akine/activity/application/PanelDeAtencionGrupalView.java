package com.akine.activity.application;

import java.util.List;

/**
 * El panel de atencion grupal de una clase (AKINE-08.05, RF-M14-011): quien estuvo y que
 * atencion tiene cada uno. Sin contenido clinico.
 *
 * @param clinica {@code false} si la oferta de la clase no genera registro clinico: la lista
 *                trae las asistencias y ninguna atencion, porque no puede existir (RN-M14-008)
 */
public record PanelDeAtencionGrupalView(
		long claseId, boolean clinica, List<ParticipanteEnAtencionView> participantes) {
}
