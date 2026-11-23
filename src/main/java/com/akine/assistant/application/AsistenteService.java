package com.akine.assistant.application;

import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.PermissionCodes;
import com.akine.assistant.domain.exception.AsistenteDeshabilitadoException;
import com.akine.assistant.domain.exception.SedeNoEncontradaException;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.FuncionIa;
import com.akine.organization.spi.FuncionesIaDelCentro;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.ZonaDeSede;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Responde una pregunta en lenguaje natural sobre los datos de una sede
 * (docs/diseno/AKINE-asistente-agente.md §5).
 *
 * <p>Orden fijo: interruptor, tenant y permiso <b>antes</b> de llamar a cualquier modelo; despues las
 * acciones con tarjeta (si la frase cuenta o pide algo) y, si no, el agente, que llama a las
 * herramientas con el mismo actor y redacta la respuesta.
 *
 * <p><b>Sin transaccion propia</b>: ver {@link AuditoriaDelAsistente}. Solo lectura en todo el
 * recorrido; las escrituras van por las acciones, con confirmacion.
 */
@Service
public class AsistenteService {

	private final AsistenteAjustes ajustes;
	private final PermissionGuard permissionGuard;
	private final ConsultorioDirectory sedes;
	private final FuncionesIaDelCentro funcionesIa;
	private final AccionesDelAsistente acciones;
	private final AgenteDelAsistente agente;
	private final Clock clock;

	/** El de Spring. El otro fija el reloj para los tests; con dos, Spring necesita que se le diga cual. */
	@Autowired
	public AsistenteService(AsistenteAjustes ajustes, PermissionGuard permissionGuard, ConsultorioDirectory sedes,
			FuncionesIaDelCentro funcionesIa, AccionesDelAsistente acciones, AgenteDelAsistente agente) {
		this(ajustes, permissionGuard, sedes, funcionesIa, acciones, agente, Clock.systemUTC());
	}

	AsistenteService(AsistenteAjustes ajustes, PermissionGuard permissionGuard, ConsultorioDirectory sedes,
			FuncionesIaDelCentro funcionesIa, AccionesDelAsistente acciones, AgenteDelAsistente agente, Clock clock) {
		this.ajustes = ajustes;
		this.permissionGuard = permissionGuard;
		this.sedes = sedes;
		this.funcionesIa = funcionesIa;
		this.acciones = acciones;
		this.agente = agente;
		this.clock = clock;
	}

	/** Sin pantalla actual: igual que {@link #consultar(ActorDelAsistente, long, String, List, Long, String)}. */
	public RespuestaDelAsistente consultar(ActorDelAsistente actor, long consultorioId, String pregunta,
			List<TurnoPrevio> historial, Long personaId) {
		return consultar(actor, consultorioId, pregunta, historial, personaId, null);
	}

	/**
	 * @param personaId  la persona que el usuario eligio entre las opciones de una aclaracion, o
	 *                   {@code null}
	 * @param rutaActual la pantalla desde la que se pregunta, o {@code null}
	 * @throws AsistenteDeshabilitadoException si el interruptor esta apagado (409)
	 * @throws AccessDeniedException sin contexto de organizacion o sin {@code asistente:use} (403)
	 * @throws SedeNoEncontradaException si la sede no es de la organizacion (404)
	 * @throws com.akine.assistant.domain.exception.ModeloNoDisponibleException si ningun modelo responde (503)
	 */
	public RespuestaDelAsistente consultar(ActorDelAsistente actor, long consultorioId, String pregunta,
			List<TurnoPrevio> historial, Long personaId, String rutaActual) {
		if (!ajustes.enabled()) {
			throw new AsistenteDeshabilitadoException();
		}
		Long organizationId = actor.organizationId();
		if (organizationId == null) {
			throw new AccessDeniedException("La operacion requiere una organizacion en contexto");
		}
		if (!funcionesIa.prendida(organizationId, FuncionIa.ASISTENTE)) {
			throw new AsistenteDeshabilitadoException();
		}
		permissionGuard.requirePermission(new PermissionQuery(actor.accountId(), PermissionCodes.ASISTENTE_USE,
				organizationId, consultorioId, null, Instant.now(clock)));
		ConsultorioSnapshot sede = sedes.find(organizationId, consultorioId)
				.orElseThrow(SedeNoEncontradaException::new);
		ZoneId zona = ZonaDeSede.de(sede);
		ActorDelAsistente enLaSede = new ActorDelAsistente(actor.accountId(), actor.platformAdmin(),
				organizationId, consultorioId, actor.roleCode());

		// Una frase que cuenta o pide algo ("Juan pagó 15.000") no es una consulta: si las acciones estan
		// prendidas se propone una tarjeta para confirmar. Audita por su cuenta, con el tipo y los ids.
		if (acciones != null) {
			Optional<RespuestaDelAsistente> propuesta = acciones.proponer(enLaSede, zona, pregunta, personaId);
			if (propuesta.isPresent()) {
				return propuesta.get();
			}
		}

		ContextoDeConsulta ctx = new ContextoDeConsulta(LocalDate.now(clock.withZone(zona)), zona, rutaActual);
		return agente.responder(enLaSede, ctx, sede.name(), pregunta, historial == null ? List.of() : historial,
				personaId);
	}
}
