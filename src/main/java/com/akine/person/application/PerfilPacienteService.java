package com.akine.person.application;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * La activacion del perfil clinico de una Persona (M07, RF-M07-008).
 *
 * <h2>Esta clase es el UNICO lugar del sistema que convierte a alguien en paciente</h2>
 *
 * <p>Y eso es el diseño entero de la etapa, no una casualidad de como quedo repartido el codigo.
 * {@code PersonaService} no escribe en {@code perfil_paciente} —solo lo lee para poder informar
 * quien es paciente en un listado— y no existe ninguna columna {@code es_paciente} que un
 * {@code UPDATE} distraido pueda poner en {@code true}. Cuando F9 traiga inscripciones a clases y
 * F5 traiga turnos, esos caminos van a poder dar de alta personas sin poder crear pacientes, que
 * es literalmente RF-M07-010.
 *
 * <p><b>Y activar el perfil TAMPOCO crea Historia Clinica.</b> La HC es M09, vive en el modulo
 * {@code clinical} que todavia no existe, y la regla maestra 1 la separa del Caso y de la Sesion.
 * Lo que esta clase escribe es una fila que dice "esta persona es paciente"; que ademas tenga
 * historia es una decision del modulo clinico, cuando exista.
 *
 * <h2>Idempotencia: activar dos veces devuelve el mismo perfil, con 200</h2>
 *
 * <p>Activar un perfil que ya existe no es un error del operador: es el boton tocado dos veces o
 * el request reintentado despues de un timeout. Responder 409 obligaria a toda pantalla a
 * distinguir "ya era paciente" de un conflicto real, y lo natural seria que terminara ignorando
 * los dos.
 *
 * <p>La garantia tiene <b>dos capas y las dos hacen falta</b>:
 *
 * <ol>
 *   <li>El pre-chequeo: si ya hay perfil vigente, se devuelve. Resuelve el caso comun sin
 *       intentar el INSERT.</li>
 *   <li>El unique {@code uk_perfil_paciente_persona}: dos requests simultaneos pasan los dos el
 *       pre-chequeo —esa es la ventana de carrera que un {@code SELECT} previo nunca cierra— y el
 *       segundo choca contra el unique. Ahi el INSERT se traduce, tambien, a la respuesta
 *       idempotente.</li>
 * </ol>
 *
 * <p>Sin la segunda capa, dos clicks rapidos producirian un 500. Sin la primera, funcionaria
 * igual pero pagando un INSERT fallido en el caso mas frecuente.
 */
@Service
public class PerfilPacienteService {

	private static final Logger log = LoggerFactory.getLogger(PerfilPacienteService.class);

	private final PersonaRepositoryPort personas;
	private final PerfilPacienteRepositoryPort perfiles;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final PersonSupportAccessAuditor supportAccessAuditor;

	public PerfilPacienteService(
			PersonaRepositoryPort personas,
			PerfilPacienteRepositoryPort perfiles,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			PersonSupportAccessAuditor supportAccessAuditor) {

		this.personas = personas;
		this.perfiles = perfiles;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
	}

	/**
	 * Activa el perfil clinico sobre una Persona existente (RF-M07-008).
	 *
	 * <p>Reutiliza la identidad y no duplica ningun dato personal (RN-M07-007): no recibe ni
	 * apellido, ni documento, ni contacto. Si la persona no existe todavia, el alta es otra
	 * operacion y ocurre antes — que es exactamente lo que hace que "activar" no pueda
	 * convertirse en un alta encubierta.
	 *
	 * <p>Una persona INACTIVA no admite activacion: 409. El caso inverso —persona vigente cuyo
	 * perfil anterior fue dado de baja— si es una activacion legitima, y el unique lo permite
	 * porque lleva {@code deleted_key}. Es el caso borde "perfil dado de baja" que la etapa lista.
	 *
	 * @return el perfil, sea el recien creado o el que ya existia
	 */
	@Transactional
	public PersonaView activar(OperatingActor actor, long personaId, String motivo) {
		PermissionDecision decision = AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Activar perfil de paciente");
		long organizationId = actor.contextOrganizationId();

		Persona persona = personas.findByIdAndOrganizationId(personaId, organizationId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));
		if (!persona.isOperable()) {
			throw new PersonaInactivaException(personaId, "activar un perfil de paciente");
		}

		Optional<PerfilPaciente> yaExistente = perfiles.buscarVigente(organizationId, personaId);
		if (yaExistente.isPresent()) {
			// Primera capa de idempotencia. NO se audita: no ocurrio ningun hecho nuevo, y
			// registrarlo llenaria la auditoria de activaciones que nunca pasaron.
			log.debug("Perfil de paciente ya vigente: personaId={}", personaId);
			return PersonaView.de(persona, yaExistente.get());
		}

		Instant ahora = Instant.now();
		PerfilPaciente perfil = new PerfilPaciente(
				organizationId, personaId, ahora, actor.accountId(), motivo);

		PerfilPaciente guardado;
		try {
			guardado = perfiles.saveAndFlush(perfil);
		} catch (DataIntegrityViolationException choque) {
			// Segunda capa: otro request gano la carrera entre el pre-chequeo y este INSERT.
			//
			// NO se vuelve a consultar la sesion JPA para recuperar el perfil ganador: despues de
			// un flush fallido la sesion es inutilizable y lo que sale de ahi es un 500 en vez de
			// una respuesta correcta. Se responde con la persona y SIN el perfil, que es
			// informacion incompleta pero cierta, y el cliente la refresca con un GET. La
			// alternativa —adivinar el perfil que existe— seria devolver un id que este metodo no
			// leyo.
			log.info("Activacion concurrente de perfil de paciente resuelta como idempotente: "
					+ "personaId={}", personaId);
			return PersonaView.de(persona);
		}

		auditar(persona, guardado, actor, motivo, ahora);
		registrarSoporte(decision, actor, persona, ahora);

		log.info("Perfil de paciente activado: personaId={} perfilId={} organizationId={}",
				personaId, guardado.getId(), organizationId);

		return PersonaView.de(persona, guardado);
	}

	private void auditar(
			Persona persona,
			PerfilPaciente perfil,
			OperatingActor actor,
			String motivo,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				persona.getOrganizationId(),
				// consultorioId NULL, mismo criterio que PersonaService: la sede del contexto
				// autorizo la operacion pero el hecho es de la organizacion.
				null,
				actor.accountId(),
				AuditEvents.PERFIL_PACIENTE_ACTIVATED,
				AuditEvents.ENTITY_PERSONA,
				persona.getId(),
				"SIN_PERFIL",
				"PACIENTE",
				Map.of("perfilPacienteId", String.valueOf(perfil.getId())),
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	/** Ver {@code PersonaService.registrarSoporte}: el mismo invariante, el mismo motivo. */
	private void registrarSoporte(
			PermissionDecision decision, OperatingActor actor, Persona persona, Instant ahora) {

		if (!decision.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				persona.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_PERSONA,
				persona.getId(),
				null,
				null,
				Map.of("operacion", "Activar perfil de paciente"),
				null,
				AuditEvents.correlationId(),
				ahora));
	}
}
