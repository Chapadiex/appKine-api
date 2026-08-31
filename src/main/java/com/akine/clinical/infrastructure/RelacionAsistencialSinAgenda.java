package com.akine.clinical.infrastructure;

import com.akine.clinical.spi.RelacionAsistencialProbe;
import org.springframework.stereotype.Component;

/**
 * Implementacion por defecto de {@link RelacionAsistencialProbe} mientras no existan turnos ni
 * sesiones.
 *
 * <p><b>Responde siempre que no hay evidencia.</b> La relacion asistencial se demuestra con un
 * turno (05.0x) o con una sesion (06.01), y ninguno de los dos existe al 31/08/2026. La
 * consecuencia practica hay que decirla completa: mientras esta clase sea la que responde,
 * <b>todo acceso a una historia clinica exige justificacion declarada</b>.
 *
 * <p>Devolver {@code false} y no {@code true} es la eleccion segura. Conceder por defecto seria
 * mas comodo hoy y dejaria el sistema abierto justo en el periodo en que nadie lo mira; ademas,
 * cuando llegara la implementacion real, el endurecimiento se veria como una regresion de producto
 * en vez de como lo que es.
 *
 * <p><b>Cuando exista la agenda, esta clase se reemplaza, no se complementa.</b> Es un bean
 * singular —el consumidor inyecta un {@code RelacionAsistencialProbe}, no una lista— asi que si
 * conviven durante una migracion, la real necesita {@code @Primary} o esta necesita
 * {@code @ConditionalOnMissingBean} para que Spring no falle por bean duplicado. Mismo patron y
 * misma trampa que {@code resource.infrastructure.DisponibilidadImpactProbeSinAgenda}.
 */
@Component
public class RelacionAsistencialSinAgenda implements RelacionAsistencialProbe {

	@Override
	public boolean tieneRelacionAsistencial(
			long organizationId, long consultorioId, long actorAccountId, long personaId) {
		return false;
	}
}
