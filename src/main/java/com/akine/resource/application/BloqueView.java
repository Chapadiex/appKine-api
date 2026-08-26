package com.akine.resource.application;

import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.spi.DisponibilidadImpactProbe;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Proyeccion de lectura de un bloque de disponibilidad. Es lo unico que cruza el borde del
 * servicio: la entity nunca sale (AGENT.md seccion 4, regla 6).
 *
 * <h2>{@code turnosAfectados} responde hoy siempre 0, y no es un bug</h2>
 *
 * <p>Es el mismo cero estructural que {@code EspacioAvailabilityResponse} declara para
 * {@code lugaresComprometidos}, y por el mismo motivo: los turnos son del modulo
 * {@code scheduling} (F5, M12), que no existe. El servicio SI consulta
 * {@link DisponibilidadImpactProbe} en la edicion y en la baja —la interfaz tiene llamador
 * desde hoy, no es codigo muerto esperando a F5—, pero la unica implementacion registrada
 * responde {@code Impacto.ninguno()}.
 *
 * <p><b>Una pantalla que interprete "cero conflictos" como "se puede cambiar sin
 * consecuencias" va a dejar turnos huerfanos en cuanto exista la agenda</b>, y el bug no va a
 * parecer de esta etapa. Cuando F5 traiga la implementacion real, este campo deja de ser cero
 * sin ningun cambio de contrato.
 *
 * @param estado               DERIVADO de {@code active}, no una columna
 * @param version              la que hay que reenviar para editar
 * @param turnosAfectados      turnos futuros que el cambio dejaria en conflicto (RN-M05-004).
 *                             En un ALTA es cero por construccion y sin preguntarle a nadie:
 *                             agregar disponibilidad no puede dejar ningun turno afuera
 * @param primerTurnoAfectado  instante del primero de esos turnos, para que la pantalla pueda
 *                             decir "desde el martes". {@code null} cuando no hay ninguno
 * @param nuevo                {@code true} SOLO cuando este pedido creo la fila. Existe para que
 *                             la capa {@code api} pueda distinguir un alta real de un alta
 *                             idempotente y responder <b>201</b> o <b>200</b> sin volver a
 *                             consultar la base. Es la unica forma de saberlo desde afuera: la
 *                             vista del bloque que ya existia es indistinguible de la del recien
 *                             creado. En toda lectura vale {@code false}, porque leer no crea
 *                             nada, y <b>no viaja en el contrato</b>: lo que el cliente ve es el
 *                             codigo HTTP
 */
public record BloqueView(
		long id,
		long organizationId,
		long consultorioId,
		long membershipId,
		int diaSemana,
		LocalTime horaDesde,
		LocalTime horaHasta,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version,
		long turnosAfectados,
		Instant primerTurnoAfectado,
		boolean nuevo) {

	/**
	 * Vista de un bloque que no cambia disponibilidad hacia atras: lectura, edicion, o el alta
	 * IDEMPOTENTE que devolvio la fila que ya existia.
	 */
	public static BloqueView de(BloqueDisponibilidad bloque) {
		return de(bloque, DisponibilidadImpactProbe.Impacto.ninguno());
	}

	public static BloqueView de(BloqueDisponibilidad bloque, DisponibilidadImpactProbe.Impacto impacto) {
		return construir(bloque, impacto, false);
	}

	/**
	 * Vista de un bloque que ESTE pedido acaba de crear.
	 *
	 * <p>Unico camino que marca {@code nuevo}. El controller lo traduce a 201 con
	 * {@code Location}; todo lo demas sale 200.
	 */
	public static BloqueView nuevo(BloqueDisponibilidad bloque) {
		return construir(bloque, DisponibilidadImpactProbe.Impacto.ninguno(), true);
	}

	private static BloqueView construir(
			BloqueDisponibilidad bloque, DisponibilidadImpactProbe.Impacto impacto, boolean nuevo) {
		return new BloqueView(
				bloque.getId(),
				bloque.getOrganizationId(),
				bloque.getConsultorioId(),
				bloque.getMembershipId(),
				bloque.getDiaSemana(),
				bloque.getHoraDesde(),
				bloque.getHoraHasta(),
				bloque.getVigenciaDesde(),
				bloque.getVigenciaHasta(),
				bloque.isActive() ? "ACTIVO" : "INACTIVO",
				bloque.getDeletedAt(),
				bloque.getDeactivationReason(),
				bloque.getVersion(),
				impacto.turnosAfectados(),
				impacto.primero(),
				nuevo);
	}
}
