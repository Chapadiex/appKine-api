package com.akine.encounter.domain.port;

import com.akine.encounter.domain.SesionReemplazoProfesional;

/**
 * Los reemplazos del profesional de una sesion (U-24). <b>Solo alta</b>: es un registro
 * append-only, y lo que no esta en la interfaz no lo puede llamar nadie por distraccion.
 */
public interface SesionReemplazoProfesionalRepositoryPort {

	SesionReemplazoProfesional save(SesionReemplazoProfesional reemplazo);
}
