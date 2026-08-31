package com.akine.person.spi;

import java.util.Optional;

/**
 * Consulta del padron para los modulos que registran hechos sobre un paciente.
 *
 * <h2>Quien lo usa y por que nace ahora</h2>
 *
 * <p>{@code clinical} (AKINE-04.01, M09) es el primer consumidor: la Historia Clinica cuelga de
 * una Persona con perfil de paciente vigente —el recableado que fija DP-10— y esa precondicion
 * hay que poder verificarla. Las tablas {@code persona} y {@code perfil_paciente} son propiedad
 * de {@code person} (AGENT.md seccion 4, regla 1), asi que la alternativa —que {@code clinical}
 * las lea directo— la rechaza ArchUnit y con razon.
 *
 * <p><b>Este puerto no autoriza nada.</b> Devuelve datos del tenant que se le pide y confia en
 * que el llamador ya evaluo el permiso; mezclar autorizacion aca obligaria a cada consumidor a
 * pasar su actor y su codigo de permiso, y la decision quedaria duplicada en dos modulos. El
 * aislamiento de tenant SI se aplica: la firma exige {@code organizationId} y no resuelve por id
 * pelado.
 *
 * <p><b>Y tampoco crea nada.</b> No hay ningun metodo que active un perfil de paciente: eso lo
 * hace {@code PerfilPacienteService} y solo el, que es el diseño entero de AKINE-03.01. Un
 * modulo clinico que pudiera convertir a alguien en paciente por el camino de crear su historia
 * seria RF-M07-010 violada desde afuera.
 */
public interface PacienteDirectory {

	/**
	 * Una persona del padron de esa organizacion, con su perfil de paciente ya resuelto.
	 *
	 * <p>Devuelve tambien las fichas dadas de baja, con {@code activa = false}: el consumidor
	 * necesita distinguir "no es tuya" —vacio, que se trata como inexistente— de "esta dada de
	 * baja", que es un conflicto y no un 404.
	 */
	Optional<PacienteSnapshot> find(long organizationId, long personaId);
}
