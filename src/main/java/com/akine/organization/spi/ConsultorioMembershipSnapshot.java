package com.akine.organization.spi;

import java.time.Instant;

/**
 * Vista de una membership para los modulos que necesitan saber DONDE y CUANDO vale.
 *
 * <p>Existe aparte de {@link MembershipSnapshot} y no como una ampliacion suya por un motivo
 * concreto: aquel record ya lo consume {@code identity}, y agregarle componentes rompe todos
 * sus constructores. Un record nuevo no rompe nada.
 *
 * <p>{@code habilitada} viaja YA CALCULADO por el adaptador a partir de
 * {@code MembershipEstado.habilita()} (fixed round 1: el primer borrador de este record
 * evaluaba vigencia solo con {@code active}, igual que {@link MembershipSnapshot}, e ignoraba
 * el estado ACTIVA/SUSPENDIDA/REVOCADA que {@code Membership.isValidAt} SI mira desde 01.03. Una
 * membership {@code SUSPENDIDA} lea como valida es exactamente lo que no puede pasar: un
 * profesional suspendido seguiria ofreciendo disponibilidad). {@code estado} viaja aparte, en
 * texto, solo para diagnostico y visualizacion: la decision de si habilita algo la toma
 * {@code habilitada}, nunca una comparacion de string en el consumidor.
 *
 * @param consultorioId sede del vinculo, o {@code null} si el alcance es la organizacion
 *                      entera. {@code null} significa <b>alcance</b>, no dato faltante:
 *                      la misma convencion de {@code membership} (V10) y de
 *                      {@code colaborador_invitacion} (V21)
 * @param estado        estado bruto del vinculo (ACTIVA/SUSPENDIDA/REVOCADA), solo para mostrar
 *                      o diagnosticar. Que habilite o no algo lo dice {@code habilitada}
 * @param habilitada    resultado de {@code MembershipEstado.habilita()} para este vinculo,
 *                      calculado en el adaptador. No es lo mismo que {@code active}: activa pero
 *                      suspendida no habilita, y las dos condiciones tienen que cumplirse
 */
public record ConsultorioMembershipSnapshot(
		long membershipId,
		long accountId,
		long organizationId,
		Long consultorioId,
		String roleCode,
		String estado,
		Instant validFrom,
		Instant validUntil,
		boolean active,
		boolean habilitada) {

	/** Vigencia, baja logica Y estado: las tres tienen que cumplirse, igual que en el dominio. */
	public boolean validAt(Instant momento) {
		if (!active || !habilitada || momento.isBefore(validFrom)) {
			return false;
		}
		return validUntil == null || momento.isBefore(validUntil);
	}

	/** {@code true} si el vinculo habilita en esa sede: la propia, o alcance organizacion. */
	public boolean cubreConsultorio(long consultorioId) {
		return this.consultorioId == null || this.consultorioId == consultorioId;
	}
}
