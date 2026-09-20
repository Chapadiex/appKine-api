package com.akine.encounter.domain.port;

import com.akine.encounter.domain.SesionVersion;

import java.util.List;

/**
 * Persistencia del historial de contenido de una sesion (06.06).
 *
 * <p>Vive en {@code domain} porque {@code application} consume puertos y nunca repositorios de
 * {@code infrastructure}: es la regla que 01.01 dejo fijada y que ArchUnit verifica.
 *
 * <p><b>Solo hay escritura de alta y lectura.</b> No existe {@code delete}, no existe
 * {@code update} y no es una omision: una version es un hecho pasado y modificarla o borrarla
 * seria reescribir historia clinica (ADR-0011, regla maestra 10). Lo que no esta en la interfaz
 * no lo puede llamar nadie por distraccion.
 */
public interface SesionVersionRepositoryPort {

	SesionVersion save(SesionVersion version);

	/**
	 * Todas las versiones de una sesion, de la 1 a la ultima (RF-M24-005).
	 *
	 * <p><b>Sin paginar, a proposito.</b> El maximo real es "cuantas veces se enmendo una atencion
	 * de 40 minutos", que es un numero de un digito; paginar obligaria a la pantalla a manejar un
	 * cursor para comparar dos versiones que casi siempre son dos filas. Es lo contrario del
	 * timeline, donde un paciente cronico tiene novecientas sesiones y el recorte es obligatorio.
	 *
	 * <p>Acota por tenant ademas de por sesion aunque {@code sesionId} ya sea unico: es lo que
	 * hace que la consulta calce con {@code uk_sesion_version_numero}, que empieza por
	 * {@code organization_id}, y lo que impide que un id de otro tenant devuelva algo si alguna
	 * vez el llamador se saltea la resolucion previa de la sesion.
	 */
	List<SesionVersion> buscarPorSesion(long organizationId, long sesionId);
}
