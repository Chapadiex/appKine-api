package com.akine.platform.spi.tenant;

import java.time.Instant;

/**
 * Unico puerto por el que {@code platform} averigua si una cuenta administra la plataforma.
 *
 * <p><b>Puerto invertido</b>, exactamente igual que {@link MembershipDirectory}: lo declara
 * {@code platform} y lo implementa {@code organization.infrastructure.tenant} leyendo
 * {@code platform_role}. Spring inyecta por interfaz, asi que {@code platform} nunca compila
 * contra {@code organization} y el grafo de modulos queda aciclico.
 *
 * <p><b>Por que existe.</b> Hasta AKINE-01.02, {@code AuthenticatedJwtPrincipal.platformAdmin()}
 * comparaba el claim {@code rol} del token contra {@code "PLATFORM_ADMIN"}. Eso contradice
 * frontalmente lo que documenta {@code AccessTokenClaims}: <i>"si alguien empieza a autorizar
 * por este campo, la ventana de revocacion de permisos deja de ser cero y pasa a ser el TTL del
 * token"</i>. Era una contradiccion inofensiva solo porque el claim nunca valia ese valor —y
 * por eso los tres endpoints de plataforma publicados en el contrato 0.3.0 no los podia
 * ejecutar nadie—. ADR-0020 crea la tabla; este puerto la lleva hasta la cadena de filtros.
 *
 * <p><b>Sin cache, a proposito.</b> Se consulta una vez por request autenticado, igual que la
 * membership (decision T-7). El costo es un seek indexado mas sobre el camino mas caliente del
 * sistema, y es el precio correcto: la ventana de revocacion del permiso mas alto del sistema
 * tiene que ser cero. Cachearlo exige un ADR nuevo.
 */
public interface PlatformRoleDirectory {

	/**
	 * Indica si la cuenta tiene un rol de plataforma vigente en ese instante.
	 *
	 * <p><b>Esto NO habilita a ver datos de un tenant.</b> Abre las rutas {@code /platform/**};
	 * entrar a los datos de una organizacion exige ademas un acceso de soporte vigente para
	 * ESA organizacion, con motivo y vencimiento (matriz seccion 7, ADR-0020).
	 *
	 * @param accountId cuenta autenticada, resuelta desde el {@code sub} del token
	 * @param at        instante contra el que se evalua la vigencia (UTC)
	 */
	boolean isPlatformAdmin(long accountId, Instant at);
}
