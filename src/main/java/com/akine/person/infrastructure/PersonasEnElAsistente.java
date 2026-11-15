package com.akine.person.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.HerramientaDelAsistente;
import com.akine.assistant.spi.Intencion;
import com.akine.assistant.spi.PedidoDeHerramienta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PersonaBusqueda;
import com.akine.person.application.PersonaEstadoFiltro;
import com.akine.person.application.PersonaPagina;
import com.akine.person.application.PersonaService;
import com.akine.person.application.PersonaView;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Busca personas del padron para que el asistente resuelva un nombre (diseno, §4.4). Llama a
 * {@link PersonaService#buscar}, que exige {@code paciente:read}.
 *
 * <p>Solo personas activas, y el documento enmascarado: alcanza para distinguir dos homonimos sin
 * mostrar el numero entero en el chat.
 */
@Component
class PersonasEnElAsistente implements HerramientaDelAsistente {

	static final int MAXIMO = 10;

	private final PersonaService personas;

	PersonasEnElAsistente(PersonaService personas) {
		this.personas = personas;
	}

	@Override
	public Intencion intencion() {
		return Intencion.BUSCAR_PERSONA;
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, PedidoDeHerramienta pedido) {
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
		PersonaPagina pagina = personas.buscar(operador,
				new PersonaBusqueda(pedido.texto(), PersonaEstadoFiltro.ACTIVO, null), 0, MAXIMO);
		List<Map<String, Object>> filas = pagina.contenido().stream().map(PersonasEnElAsistente::fila).toList();
		return new ResultadoDeHerramienta(filas, List.of(), null, false);
	}

	private static Map<String, Object> fila(PersonaView persona) {
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put(ClavesDeFila.PERSONA_ID, persona.id());
		fila.put(ClavesDeFila.NOMBRE, persona.apellido() + ", " + persona.nombre());
		fila.put(ClavesDeFila.DOCUMENTO, enmascarado(persona.tipoDocumento(), persona.numeroDocumento()));
		return fila;
	}

	/** "DNI ***456". Sin documento, {@code null}. */
	static String enmascarado(String tipo, String numero) {
		if (numero == null || numero.isBlank()) {
			return null;
		}
		String ultimos = numero.length() <= 3 ? numero : numero.substring(numero.length() - 3);
		return (tipo == null ? "" : tipo + " ") + "***" + ultimos;
	}
}
