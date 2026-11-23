package com.akine.person.infrastructure;

import com.akine.assistant.spi.AccionDelAsistente;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.DatosDePersona;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.PropuestaDeAccion;
import com.akine.assistant.spi.ResultadoDeAccion;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.OpcionDeTarjeta;
import com.akine.assistant.spi.Tarjeta.TarjetaDePaciente;
import com.akine.assistant.spi.TipoDeAccion;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PerfilPacienteService;
import com.akine.person.application.PersonaAltaCommand;
import com.akine.person.application.PersonaBusqueda;
import com.akine.person.application.PersonaEstadoFiltro;
import com.akine.person.application.PersonaService;
import com.akine.person.application.PersonaView;
import com.akine.person.domain.ClaveDeBusqueda;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.exception.PersonaDocumentoTakenException;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.exception.PersonaPosibleDuplicadoException;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * El alta de un paciente dicha al asistente (diseno de acciones, §6.5): crea la persona y le activa
 * el perfil de paciente, las dos cosas en una transaccion.
 *
 * <p>Los permisos los exigen los servicios: {@code paciente:read} para mostrar a los parecidos y
 * {@code paciente:manage} para grabar. No tiene proteccion propia contra la repeticion: la da el
 * estado {@code EN_CURSO} de la propuesta.
 */
@Component
class AltaDePacientePorElAsistente implements AccionDelAsistente {

	/** El tipo de documento que se propone: la frase solo trae el numero. */
	static final TipoDocumento TIPO_POR_DEFECTO = TipoDocumento.DNI;

	static final String MOTIVO = "Alta desde el asistente";

	static final String FALTA_EL_NOMBRE = "Falta el nombre y el apellido.";

	/** Cuantos parecidos se muestran como mucho. */
	static final int MAXIMO_DE_DUPLICADOS = 5;

	private final PersonaService personas;
	private final PerfilPacienteService perfiles;
	private final PersonaRepositoryPort padron;
	private final TransactionTemplate transaccion;

	AltaDePacientePorElAsistente(PersonaService personas, PerfilPacienteService perfiles,
			PersonaRepositoryPort padron, PlatformTransactionManager transacciones) {
		this.personas = personas;
		this.perfiles = perfiles;
		this.padron = padron;
		this.transaccion = new TransactionTemplate(transacciones);
	}

	@Override
	public TipoDeAccion tipo() {
		return TipoDeAccion.PACIENTE_ALTA;
	}

	@Override
	public PropuestaDeAccion preparar(ActorDelAsistente actor, PedidoDeAccion pedido) {
		OperatingActor operador = operador(actor);
		DatosDePersona datos = pedido.datos();
		// Una lectura del padron por el servicio antes que nada: exige paciente:read. Sin ella, quien
		// no puede leer el padron sabria si existe alguien con ese nombre segun reciba una tarjeta o un
		// "sin permiso" (la busqueda de parecidos va por el puerto y no autoriza).
		personas.buscar(operador, new PersonaBusqueda(null, PersonaEstadoFiltro.ACTIVO, null), 0, 1);
		List<String> avisos = new ArrayList<>();
		List<String> bloqueos = new ArrayList<>();
		List<OpcionDeTarjeta> duplicados = List.of();

		if (enBlanco(datos.nombre()) || enBlanco(datos.apellido())) {
			bloqueos.add(FALTA_EL_NOMBRE);
		} else {
			duplicados = posiblesDuplicados(operador, datos);
			if (!duplicados.isEmpty()) {
				avisos.add("Ya hay personas parecidas: revisá que no sea la misma.");
			}
		}

		PersonaView titular = titularDelDocumento(operador, datos.documento());
		if (titular != null) {
			bloqueos.add("Ese documento ya es de " + nombreDe(titular) + ".");
		}

		Tarjeta tarjeta = new TarjetaDePaciente(null, null, datos, TIPO_POR_DEFECTO.name(), null, duplicados, false);
		return new PropuestaDeAccion("Alta de paciente", tarjeta, Map.of(), avisos, bloqueos, null);
	}

	@Override
	public ResultadoDeAccion ejecutar(ActorDelAsistente actor, long accionId, Tarjeta confirmada) {
		if (!(confirmada instanceof TarjetaDePaciente tarjeta) || tarjeta.personaId() != null
				|| enBlanco(tarjeta.datos().nombre()) || enBlanco(tarjeta.datos().apellido())) {
			return ResultadoDeAccion.rechazada(FALTA_EL_NOMBRE);
		}
		DatosDePersona datos = tarjeta.datos();
		String invalido = invalido(datos);
		if (invalido != null) {
			return ResultadoDeAccion.rechazada(invalido);
		}
		TipoDocumento tipoDocumento = null;
		if (!enBlanco(datos.documento())) {
			tipoDocumento = tipoDe(tarjeta.tipoDocumento());
			if (tipoDocumento == null) {
				return ResultadoDeAccion.rechazada(TIPO_INVALIDO);
			}
		}
		PersonaAltaCommand alta = new PersonaAltaCommand(
				tipoDocumento,
				limpio(datos.documento()),
				datos.apellido().strip(),
				datos.nombre().strip(),
				datos.fechaNacimiento(),
				limpio(datos.email()),
				limpio(datos.telefono()),
				null,
				tarjeta.confirmaPosibleDuplicado());

		OperatingActor operador = operador(actor);
		PersonaView creada;
		try {
			// Las dos escrituras o ninguna: una persona sin perfil no es lo que se pidio.
			creada = transaccion.execute(estado -> {
				PersonaView persona = personas.crear(operador, alta);
				return perfiles.activar(operador, persona.id(), MOTIVO);
			});
		} catch (PersonaDocumentoTakenException tomado) {
			return ResultadoDeAccion.rechazada("Ese documento ya está registrado.");
		} catch (PersonaPosibleDuplicadoException duplicado) {
			return ResultadoDeAccion.rechazada("Hay personas parecidas: marcá que es otra persona para continuar.");
		} catch (PersonaInactivaException | PersonaNotAccessibleException inaccesible) {
			return ResultadoDeAccion.rechazada("No pude completar el alta: volvé a pedirlo.");
		} catch (IllegalArgumentException datoInvalido) {
			return ResultadoDeAccion.rechazada(DATOS_INVALIDOS);
		}
		return ResultadoDeAccion.confirmada("Di de alta a " + nombreDe(creada) + " como paciente.",
				"/pacientes/" + creada.id(), Map.of(PropuestaDeAccion.PERSONA, creada.id()));
	}

	/**
	 * Los que el alta rechazaria como posible duplicado: la misma consulta que usa
	 * {@code PersonaService.crear}. El puerto solo da los ids; cada uno se lee por el servicio, que
	 * exige {@code paciente:read}.
	 */
	private List<OpcionDeTarjeta> posiblesDuplicados(OperatingActor operador, DatosDePersona datos) {
		if (operador.contextOrganizationId() == null) {
			return List.of();
		}
		List<Persona> candidatos = padron.buscarCoincidencias(
				operador.contextOrganizationId(),
				ClaveDeBusqueda.deNombre(datos.apellido()),
				ClaveDeBusqueda.deNombre(datos.nombre()),
				ClaveDeBusqueda.deTelefono(datos.telefono()));
		return candidatos.stream()
				.limit(MAXIMO_DE_DUPLICADOS)
				.map(candidato -> personas.ver(operador, candidato.getId()))
				.map(AltaDePacientePorElAsistente::opcion)
				.toList();
	}

	private static OpcionDeTarjeta opcion(PersonaView persona) {
		String documento = PersonasEnElAsistente.enmascarado(persona.tipoDocumento(), persona.numeroDocumento());
		return new OpcionDeTarjeta(persona.id(), nombreDe(persona) + (documento == null ? "" : " · " + documento));
	}

	/** La persona activa que ya tiene ese numero con el tipo por defecto, o {@code null}. */
	private PersonaView titularDelDocumento(OperatingActor operador, String documento) {
		String clave = ClaveDeBusqueda.deDocumento(documento);
		if (clave == null) {
			return null;
		}
		return personas.buscar(operador, new PersonaBusqueda(documento, PersonaEstadoFiltro.ACTIVO, null), 0,
						PersonasEnElAsistente.MAXIMO)
				.contenido().stream()
				.filter(persona -> TIPO_POR_DEFECTO.name().equals(persona.tipoDocumento())
						&& clave.equals(ClaveDeBusqueda.deDocumento(persona.numeroDocumento())))
				.findFirst()
				.orElse(null);
	}

	// =================================================================================
	// Lo que comparte con la edicion
	// =================================================================================

	static final String TIPO_INVALIDO = "Elegí un tipo de documento válido.";

	static final String DATOS_INVALIDOS = "Revisá los datos: hay alguno incompleto o que no es válido.";

	static OperatingActor operador(ActorDelAsistente actor) {
		return new OperatingActor(actor.accountId(), actor.platformAdmin(), actor.organizationId(),
				actor.consultorioId());
	}

	static String nombreDe(PersonaView persona) {
		return persona.apellido() + ", " + persona.nombre();
	}

	static boolean enBlanco(String valor) {
		return valor == null || valor.isBlank();
	}

	/** Sin espacios alrededor; en blanco es "no vino". */
	static String limpio(String valor) {
		return enBlanco(valor) ? null : valor.strip();
	}

	/** El tipo que eligio la persona, el por defecto si no vino, o {@code null} si no existe. */
	static TipoDocumento tipoDe(String tipo) {
		if (enBlanco(tipo)) {
			return TIPO_POR_DEFECTO;
		}
		try {
			return TipoDocumento.valueOf(tipo.strip().toUpperCase());
		} catch (IllegalArgumentException desconocido) {
			return null;
		}
	}

	/**
	 * Los mismos limites que la pantalla valida en {@code CreatePersonaRequest} y
	 * {@code UpdatePersonaRequest}: este camino no pasa por ellos, y sin esto un dato largo termina
	 * en un error de la base. Devuelve el motivo, o {@code null} si esta todo bien.
	 */
	static String invalido(DatosDePersona datos) {
		if (largo(datos.apellido()) > 120) {
			return "El apellido no puede superar los 120 caracteres.";
		}
		if (largo(datos.nombre()) > 120) {
			return "El nombre no puede superar los 120 caracteres.";
		}
		if (largo(datos.documento()) > 32) {
			return "El número de documento no puede superar los 32 caracteres.";
		}
		if (largo(datos.email()) > 320) {
			return "El correo no puede superar los 320 caracteres.";
		}
		if (largo(datos.telefono()) > 40) {
			return "El teléfono no puede superar los 40 caracteres.";
		}
		if (datos.fechaNacimiento() != null && !datos.fechaNacimiento().isBefore(LocalDate.now())) {
			return "La fecha de nacimiento tiene que ser anterior a hoy.";
		}
		return null;
	}

	private static int largo(String valor) {
		return valor == null ? 0 : valor.strip().length();
	}
}
