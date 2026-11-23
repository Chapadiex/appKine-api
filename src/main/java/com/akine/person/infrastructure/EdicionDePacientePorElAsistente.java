package com.akine.person.infrastructure;

import com.akine.assistant.spi.AccionDelAsistente;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.DatosDePersona;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.PropuestaDeAccion;
import com.akine.assistant.spi.ResultadoDeAccion;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.TarjetaDePaciente;
import com.akine.assistant.spi.TipoDeAccion;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PersonaEdicionCommand;
import com.akine.person.application.PersonaService;
import com.akine.person.application.PersonaView;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.exception.PersonaDocumentoTakenException;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.akine.person.infrastructure.AltaDePacientePorElAsistente.limpio;
import static com.akine.person.infrastructure.AltaDePacientePorElAsistente.nombreDe;
import static com.akine.person.infrastructure.AltaDePacientePorElAsistente.operador;

/**
 * El cambio de datos de una persona dicho al asistente (diseno de acciones, §6.5): telefono, mail,
 * documento o fecha de nacimiento. La tarjeta muestra antes y despues, y se graba con la version
 * leida al preparar.
 *
 * <p>Lee con {@code PersonaService.ver} ({@code paciente:read}) y graba con
 * {@code PersonaService.editar} ({@code paciente:manage}), el mismo camino que la pantalla. El
 * nombre y el apellido no se cambian por aca. Un campo en blanco es "no vino": por aca no se vacia
 * un dato.
 */
@Component
class EdicionDePacientePorElAsistente implements AccionDelAsistente {

	static final String SIN_CAMBIOS = "Decime qué dato cambiar: teléfono, mail, documento o fecha de nacimiento.";

	static final String FICHA_DE_BAJA = "La ficha está dada de baja: no se puede modificar.";

	private final PersonaService personas;

	EdicionDePacientePorElAsistente(PersonaService personas) {
		this.personas = personas;
	}

	@Override
	public TipoDeAccion tipo() {
		return TipoDeAccion.PACIENTE_EDICION;
	}

	@Override
	public PropuestaDeAccion preparar(ActorDelAsistente actor, PedidoDeAccion pedido) {
		if (pedido.personaId() == null) {
			return PropuestaDeAccion.sinTarjeta("Decime de quién querés cambiar los datos.");
		}
		DatosDePersona datos = soloLoEditable(pedido.datos());
		if (datos.vacio()) {
			return PropuestaDeAccion.sinTarjeta(SIN_CAMBIOS);
		}
		PersonaView persona;
		try {
			persona = personas.ver(operador(actor), pedido.personaId());
		} catch (PersonaNotAccessibleException inaccesible) {
			return PropuestaDeAccion.sinTarjeta("No encontré a esa persona.");
		}
		DatosDePersona anterior = new DatosDePersona(persona.nombre(), persona.apellido(),
				persona.numeroDocumento(), persona.telefono(), persona.email(), persona.fechaNacimiento());
		String tipoDocumento = persona.tipoDocumento() == null
				? AltaDePacientePorElAsistente.TIPO_POR_DEFECTO.name()
				: persona.tipoDocumento();
		List<String> bloqueos = "ACTIVO".equals(persona.estado()) ? List.of() : List.of(FICHA_DE_BAJA);

		Tarjeta tarjeta = new TarjetaDePaciente(persona.id(), persona.version(), datos, tipoDocumento, anterior,
				List.of(), false);
		return new PropuestaDeAccion("Cambio de datos de " + nombreDe(persona), tarjeta,
				Map.of(PropuestaDeAccion.PERSONA, persona.id()), List.of(), bloqueos, null);
	}

	@Override
	public ResultadoDeAccion ejecutar(ActorDelAsistente actor, long accionId, Tarjeta confirmada) {
		if (!(confirmada instanceof TarjetaDePaciente tarjeta) || tarjeta.personaId() == null
				|| tarjeta.version() == null) {
			return ResultadoDeAccion.rechazada("No pude identificar la ficha: volvé a pedirlo.");
		}
		DatosDePersona datos = soloLoEditable(tarjeta.datos());
		if (datos.vacio()) {
			return ResultadoDeAccion.rechazada(SIN_CAMBIOS);
		}
		String invalido = AltaDePacientePorElAsistente.invalido(datos);
		if (invalido != null) {
			return ResultadoDeAccion.rechazada(invalido);
		}
		// El documento es tipo + numero: el tipo solo viaja si se cambia el numero. Sin tipo,
		// Persona.updateDatos no toca el documento.
		TipoDocumento tipoDocumento = null;
		if (datos.documento() != null) {
			tipoDocumento = AltaDePacientePorElAsistente.tipoDe(tarjeta.tipoDocumento());
			if (tipoDocumento == null) {
				return ResultadoDeAccion.rechazada(AltaDePacientePorElAsistente.TIPO_INVALIDO);
			}
		}
		// PATCH: lo que va en null queda como estaba.
		PersonaEdicionCommand edicion = new PersonaEdicionCommand(
				tipoDocumento,
				datos.documento(),
				null,
				null,
				datos.fechaNacimiento(),
				datos.email(),
				datos.telefono(),
				null,
				tarjeta.version());

		OperatingActor operador = operador(actor);
		PersonaView guardada;
		try {
			guardada = personas.editar(operador, tarjeta.personaId(), edicion);
		} catch (OptimisticLockingFailureException versionVieja) {
			return ResultadoDeAccion.rechazada("Alguien modificó la ficha: volvé a pedirlo.");
		} catch (PersonaDocumentoTakenException tomado) {
			return ResultadoDeAccion.rechazada("Ese documento ya está registrado.");
		} catch (PersonaInactivaException inactiva) {
			return ResultadoDeAccion.rechazada(FICHA_DE_BAJA);
		} catch (PersonaNotAccessibleException inaccesible) {
			return ResultadoDeAccion.rechazada("No encontré a esa persona.");
		} catch (IllegalArgumentException datoInvalido) {
			return ResultadoDeAccion.rechazada(AltaDePacientePorElAsistente.DATOS_INVALIDOS);
		}
		return ResultadoDeAccion.confirmada("Actualicé los datos de " + nombreDe(guardada) + ".",
				"/pacientes/" + guardada.id(), Map.of(PropuestaDeAccion.PERSONA, guardada.id()));
	}

	/** Telefono, mail, documento y fecha de nacimiento; nombre y apellido van en {@code null}. */
	private static DatosDePersona soloLoEditable(DatosDePersona datos) {
		return new DatosDePersona(null, null, limpio(datos.documento()), limpio(datos.telefono()),
				limpio(datos.email()), datos.fechaNacimiento());
	}
}
