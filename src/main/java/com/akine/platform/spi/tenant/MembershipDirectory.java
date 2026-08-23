package com.akine.platform.spi.tenant;

import java.time.Instant;
import java.util.Optional;

/**
 * Unico puerto de datos de membership que consume {@code platform} (decision T-3).
 *
 * <p>El diseno original tenia tres abstracciones leyendo la misma tabla:
 * {@code TenantAccessValidator}, {@code MembershipDirectory} y
 * {@code organization.spi.AccountContextDirectory}. Se consolidaron:
 * {@code TenantAccessValidator} <b>no existe</b> —su logica es esta consulta mas el estado de la
 * suscripcion, y la composicion la hace {@code TenantContextFilter}—; y
 * {@code AccountContextDirectory} queda del lado de {@code organization} para el flujo de login,
 * que es otra pregunta.
 *
 * <p><b>Puerto invertido.</b> Lo declara {@code platform} y lo implementa
 * {@code organization.infrastructure.tenant}. Spring inyecta por interfaz, asi que
 * {@code platform} nunca compila contra {@code organization} y el grafo de modulos queda
 * aciclico. Cambiar esto por una llamada directa al servicio de {@code organization} rompe
 * {@code sin_ciclos_entre_modulos}.
 *
 * <p><b>Sin cache, a proposito (T-7).</b> Se consulta en CADA request. Cachear el resultado
 * abriria una ventana en la que un acceso revocado sigue funcionando; el requisito es ventana
 * cero: una membership revocada deja de servir en el request siguiente. El costo es un seek
 * indexado por request, y es el precio correcto. Si alguna vez el SLO exige cache, es un ADR
 * nuevo, no una optimizacion silenciosa.
 */
public interface MembershipDirectory {

	/**
	 * Resuelve si una cuenta puede operar en un contexto concreto, en un instante dado.
	 *
	 * <p>Devuelve vacio cuando el contexto <b>no es accesible</b>, sin distinguir por que: no
	 * existe la organizacion, no existe el consultorio, el consultorio es de otro tenant, la
	 * membership no existe, esta dada de baja o su vigencia esta vencida. Esa indistincion es
	 * deliberada: el llamador responde <b>404</b> en todos los casos, porque diferenciar
	 * "no existe" de "existe pero no es tuyo" filtra la existencia de datos de otro tenant.
	 *
	 * <p>Lo que <b>si</b> devuelve, cuando la membership es valida, es el estado operativo del
	 * tenant aunque sea {@code CANCELADA} o {@code BAJA}: la decision de que hacer con cada
	 * estado es del llamador, no del directorio.
	 *
	 * @param accountId       cuenta autenticada
	 * @param organizationId  organizacion pedida
	 * @param consultorioId   consultorio pedido; debe pertenecer a esa organizacion
	 * @param at              instante contra el que se evalua la vigencia (UTC)
	 * @return la membership resuelta, o vacio si el contexto no es accesible
	 */
	Optional<TenantMembership> resolveMembership(
			long accountId, long organizationId, long consultorioId, Instant at);
}
