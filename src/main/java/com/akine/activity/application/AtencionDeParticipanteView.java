package com.akine.activity.application;

import com.akine.encounter.spi.AtencionDeParticipante;

/**
 * La atencion clinica de un participante, con la inscripcion por la que la pantalla lo conoce
 * (AKINE-08.05). Ids, estado y numeros: nada clinico.
 */
public record AtencionDeParticipanteView(long inscripcionId, AtencionDeParticipante atencion) {
}
