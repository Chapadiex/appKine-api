package com.akine.resource.infrastructure;

import com.akine.resource.spi.DisponibilidadImpactProbe;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Implementacion por defecto de {@link DisponibilidadImpactProbe} mientras {@code scheduling}
 * (F5, M12) no existe.
 *
 * <h2>Por que hay que registrarla como bean, y no dejar la interfaz sin implementacion</h2>
 *
 * <p>{@code DisponibilidadService} (AKINE-02.04 tarea 7) inyecta un {@code DisponibilidadImpactProbe}
 * unico por constructor, no una {@code List<>}: sin un bean de este tipo el contexto de Spring no
 * levanta apenas exista esa dependencia. La alternativa —hacer el parametro opcional y tolerar
 * {@code null} en el servicio— traslada la ausencia de {@code scheduling} a cada lugar que
 * consulte la sonda, en vez de resolverla una sola vez aca. {@code EspacioOccupancyProbe} no
 * necesita este bean por el motivo contrario: ese SPI admite lista vacia porque varias fuentes
 * pueden competir por el mismo lugar fisico; este no tiene una segunda fuente con la que
 * convivir (ver el javadoc de {@link DisponibilidadImpactProbe}).
 *
 * <p><b>Cuando F5 implemente {@code scheduling}, esta clase se reemplaza, no se complementa.</b>
 * La forma mas simple es borrarla y que el modulo nuevo registre su propio
 * {@code DisponibilidadImpactProbe}; si conviven durante una migracion, la real necesita
 * {@code @Primary} o esta necesita {@code @ConditionalOnMissingBean} para que Spring no falle
 * por bean duplicado. Hoy es la unica, asi que ninguna de las dos anotaciones hace falta.
 */
@Component
public class DisponibilidadImpactProbeSinAgenda implements DisponibilidadImpactProbe {

	@Override
	public Impacto turnosEn(
			long organizationId, long consultorioId, long membershipId, Instant desde, Instant hasta) {
		return Impacto.ninguno();
	}
}
