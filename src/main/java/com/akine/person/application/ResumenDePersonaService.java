package com.akine.person.application;

import com.akine.organization.spi.PermissionEvaluator;
import com.akine.person.domain.Persona;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.port.PersonRepositoryPorts.AdjuntoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.person.spi.AporteDeResumen;
import com.akine.person.spi.ConsultaDeResumen;
import com.akine.person.spi.ResumenDePersonaContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El Paciente 360: la ficha administrativa consolidada de una Persona (RF-M07-004).
 *
 * <h2>Que consolida, y por que no consolida mas</h2>
 *
 * <p>RF-M07-004 pide "cobertura, turnos, casos y situacion economica <b>segun permisos</b>". Lo
 * que 03.02 entrego son la identidad, los adjuntos, los turnos y la situacion economica. Las otras
 * dos tuvieron destinos distintos y conviene no confundirlos:
 *
 * <ul>
 *   <li><b>Cobertura: llego despues, como contribuyente.</b> 03.04 trajo la cobertura del
 *       paciente y B-5 la sumo con {@link CoberturasEnElResumenDePersona}, sin tocar una linea de
 *       este servicio: es la prueba de que el 360 crece por contribuyentes.</li>
 *   <li><b>Casos clinicos: existen y NO se muestran, a proposito.</b> AKINE-04.01 dejo fijado que
 *       todo acceso clinico exige justificacion declarada y queda auditado. Una ficha de mostrador
 *       que muestre casos al abrirla convertiria ese control en un formalismo: la justificacion
 *       dejaria de ser una decision del profesional para pasar a ser un efecto de haber hecho
 *       click en una persona. Quien necesita el dato clinico entra por M09 con su permiso y su
 *       justificacion, y ese acceso queda registrado como lo que es.</li>
 * </ul>
 *
 * <h2>"Segun permisos" significa recortar, no rechazar</h2>
 *
 * <p>Una seccion cuyo permiso el actor no tiene <b>no se pide y no produce un 403</b>: el 360
 * devuelve lo que el actor puede ver y <b>declara que omitio</b> y por que codigo de permiso.
 *
 * <p>Devolver 403 sobre la ficha entera por no poder ver la deuda dejaria al profesional sin poder
 * abrir a ningun paciente; y omitir en silencio seria peor todavia, porque el operador leeria "sin
 * turnos" donde en realidad dice "no podes ver los turnos" y tomaria decisiones sobre un vacio que
 * no es un vacio. Las secciones omitidas viajan explicitas para que la pantalla pueda decirlo.
 *
 * <h2>Los permisos se piden UNA vez</h2>
 *
 * <p>Con {@code effectivePermissions}, no con un {@code evaluate} por contribuyente: con cuatro
 * secciones serian cuatro resoluciones de memberships y grants para responder una sola pantalla, y
 * la ficha 360 es de las que se abren todo el dia.
 */
@Service
public class ResumenDePersonaService {

	private static final Logger log = LoggerFactory.getLogger(ResumenDePersonaService.class);

	/**
	 * Cuantos hechos datados pide el 360 a cada modulo.
	 *
	 * <p>Cinco: es una ficha, no un historico. El listado completo de turnos o de obligaciones lo
	 * sirve el modulo duenio con su propia paginacion, y traerlo entero aca convertiria cada
	 * apertura de ficha en varias consultas sin tope.
	 */
	private static final int HITOS_POR_SECCION = 5;

	private final PersonaRepositoryPort personas;
	private final PerfilPacienteRepositoryPort perfiles;
	private final AdjuntoRepositoryPort adjuntos;
	private final PermissionEvaluator permissionEvaluator;
	private final List<ResumenDePersonaContributor> contribuyentes;

	public ResumenDePersonaService(
			PersonaRepositoryPort personas,
			PerfilPacienteRepositoryPort perfiles,
			AdjuntoRepositoryPort adjuntos,
			PermissionEvaluator permissionEvaluator,
			List<ResumenDePersonaContributor> contribuyentes) {

		this.personas = personas;
		this.perfiles = perfiles;
		this.adjuntos = adjuntos;
		this.permissionEvaluator = permissionEvaluator;
		// Spring inyecta la lista vacia cuando no hay ninguna implementacion, que es un estado
		// valido: un 360 con la identidad y los adjuntos y nada mas sigue siendo un 360.
		this.contribuyentes = List.copyOf(contribuyentes);
	}

	/**
	 * La ficha 360 de una persona.
	 *
	 * <p>Se autoriza por <b>pertenencia al tenant</b>, igual que ver la ficha suelta: no hay
	 * {@code paciente:read} en la matriz y una etapa no la amplia. Lo que si filtra por permiso son
	 * las secciones que aportan los otros modulos.
	 *
	 * <p><b>Una persona INACTIVA devuelve 200.</b> RN-M07-004: el 360 de una ficha dada de baja es
	 * justamente donde se consulta su historico.
	 */
	@Transactional(readOnly = true)
	public ResumenDePersonaView ver(OperatingActor actor, long personaId) {
		long organizationId = AutorizacionDePadron.exigirContexto(actor, "Ver el resumen 360");

		Persona persona = personas.findByIdAndOrganizationId(personaId, organizationId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));

		PersonaView ficha = PersonaView.de(
				persona, perfiles.buscarVigente(organizationId, personaId).orElse(null));

		Map<String, Long> adjuntosPorCategoria = new LinkedHashMap<>();
		long adjuntosTotal = 0;
		for (Object[] fila : adjuntos.contarVigentesPorCategoria(organizationId, personaId)) {
			long cantidad = ((Number) fila[1]).longValue();
			adjuntosPorCategoria.put(String.valueOf(fila[0]), cantidad);
			adjuntosTotal += cantidad;
		}

		Set<String> permisos = permissionEvaluator.effectivePermissions(
				actor.accountId(), organizationId, actor.consultorioId());

		ConsultaDeResumen consulta = new ConsultaDeResumen(
				organizationId, actor.consultorioId(), personaId, HITOS_POR_SECCION);

		List<AporteDeResumen> secciones = new ArrayList<>();
		List<SeccionOmitida> omitidas = new ArrayList<>();

		for (ResumenDePersonaContributor contribuyente : contribuyentes) {
			String permiso = contribuyente.permisoRequerido();
			if (permiso != null && !permisos.contains(permiso)) {
				omitidas.add(new SeccionOmitida(contribuyente.seccion(), permiso));
				continue;
			}
			AporteDeResumen aporte = contribuyente.aportar(consulta);
			secciones.add(aporte == null ? AporteDeResumen.vacio(contribuyente.seccion()) : aporte);
		}

		log.debug("Resumen 360 resuelto: personaId={} secciones={} omitidas={}",
				personaId, secciones.size(), omitidas.size());

		return new ResumenDePersonaView(
				ficha, adjuntosTotal, adjuntosPorCategoria, secciones, omitidas);
	}

	/**
	 * Una seccion que el actor no puede ver, con el codigo de permiso que le falta.
	 *
	 * <p>Viaja el codigo y no una frase: la pantalla decide como decirlo, y el frontend ya conoce
	 * el catalogo de permisos porque {@code /me/permissions} se lo entrega.
	 */
	public record SeccionOmitida(String seccion, String permisoRequerido) {
	}
}
