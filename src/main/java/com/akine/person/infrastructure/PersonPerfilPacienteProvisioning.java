package com.akine.person.infrastructure;

import com.akine.person.application.OperatingActor;
import com.akine.person.application.PerfilPacienteService;
import com.akine.person.application.PersonaView;
import com.akine.person.spi.ActivacionDePerfilPaciente;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.person.spi.PerfilPacienteProvisioning;
import org.springframework.stereotype.Component;

/**
 * El unico implementador de {@link PerfilPacienteProvisioning}, y <b>no escribe nada</b>.
 *
 * <p>Esa es toda su razon de ser. RF-M07-010 y AKINE-03.01 dejaron fijado que una sola clase del
 * sistema inserta en {@code perfil_paciente}: {@link PerfilPacienteService}. Esta clase la
 * <b>expone</b>, no la reemplaza — mirar el cuerpo del metodo alcanza para verificarlo: hay una
 * traduccion de records y una llamada, y ningun repositorio inyectado.
 *
 * <p>Como delega, hereda las tres cosas que hacen que exponerla sea seguro:
 *
 * <ol>
 *   <li>La <b>idempotencia de dos capas</b> —pre-chequeo mas
 *       {@code uk_perfil_paciente_persona}— que resiste el doble click y la carrera.</li>
 *   <li>El permiso <b>{@code paciente:manage} sobre la sede del contexto</b>, que no se saltea por
 *       venir de otro modulo: un profesional clinico que no gestione el padron recibe 403.</li>
 *   <li>La <b>auditoria</b>, escrita dentro de la transaccion del negocio.</li>
 * </ol>
 *
 * <p><b>No es {@code @Transactional}</b>: lo es el metodo al que delega, y agregarle una
 * transaccion externa solo alargaria la del llamador sin ganar una sola invariante.
 */
@Component
public class PersonPerfilPacienteProvisioning implements PerfilPacienteProvisioning {

	private final PerfilPacienteService perfiles;

	public PersonPerfilPacienteProvisioning(PerfilPacienteService perfiles) {
		this.perfiles = perfiles;
	}

	@Override
	public PacienteSnapshot asegurarPerfilVigente(ActivacionDePerfilPaciente activacion) {
		PersonaView persona = perfiles.activar(
				new OperatingActor(
						activacion.accountId(),
						activacion.platformAdmin(),
						activacion.organizationId(),
						activacion.consultorioId()),
				activacion.personaId(),
				activacion.motivo());

		// La organizacion sale del contexto del actor y no de la vista: PersonaView no la lleva,
		// justamente porque una persona siempre se lee dentro de un tenant ya resuelto.
		return new PacienteSnapshot(
				persona.id(),
				activacion.organizationId(),
				persona.apellido(),
				persona.nombre(),
				persona.tipoDocumento(),
				persona.numeroDocumento(),
				persona.fechaNacimiento(),
				"ACTIVO".equals(persona.estado()),
				persona.esPaciente(),
				persona.perfilPacienteId(),
				persona.perfilActivadoEn());
	}
}
