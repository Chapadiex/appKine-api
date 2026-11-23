package com.akine.assistant.application;

import com.akine.assistant.application.RespuestaDelAsistente.Resultado;
import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.platform.infrastructure.modelo.agente.ProveedorDeAgenteDePrueba;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.modelo.agente.LlamadaAHerramienta;
import com.akine.platform.spi.modelo.agente.ProveedorDeAgente;
import com.akine.platform.spi.modelo.agente.TurnoDelAgente;
import com.akine.platform.spi.modelo.agente.UsoDelModelo;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgenteDelAsistenteTest {

	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, 7L, 3L, "ADMINISTRATIVO");
	private static final ContextoDeConsulta CTX = new ContextoDeConsulta(LocalDate.of(2026, 11, 23),
			ZoneId.of("America/Argentina/Cordoba"), null);

	private final List<AuditEntry> auditados = new ArrayList<>();
	private final AtomicInteger llamadasABuscar = new AtomicInteger();

	/** Devuelve dos personas con el mismo apellido. */
	private final HerramientaDelAgente buscarPersona = new HerramientaDelAgente() {
		@Override
		public String nombre() {
			return "buscar_persona";
		}

		@Override
		public String descripcion() {
			return "Busca personas por nombre";
		}

		@Override
		public String esquemaEntrada() {
			return "{\"type\":\"object\",\"properties\":{\"nombre\":{\"type\":\"string\"}},\"additionalProperties\":false}";
		}

		@Override
		public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
				ContextoDeConsulta ctx) {
			llamadasABuscar.incrementAndGet();
			return new ResultadoDeHerramienta(List.of(
					Map.of(ClavesDeFila.PERSONA_ID, 1L, ClavesDeFila.NOMBRE, "Pérez, Juan", ClavesDeFila.DOCUMENTO, "***456"),
					Map.of(ClavesDeFila.PERSONA_ID, 2L, ClavesDeFila.NOMBRE, "Pérez, Juana")), List.of(), null, false);
		}
	};

	private AgenteDelAsistente agente(ProveedorDeAgente principal, ProveedorDeAgente alternativo) {
		return new AgenteDelAsistente(new ProveedoresDelAgente(principal, alternativo),
				new CatalogoDeHerramientas(List.of(buscarPersona)), new AuditoriaDelAsistente(auditados::add),
				"SYSTEM", 8, Duration.ofSeconds(30), Clock.systemUTC());
	}

	private static TurnoDelAgente pide(String herramienta, String entrada) {
		return new TurnoDelAgente(null, List.of(new LlamadaAHerramienta("l1", herramienta, entrada)), null,
				new UsoDelModelo(10, 2, 1));
	}

	private static TurnoDelAgente final_(String texto) {
		return new TurnoDelAgente(texto, List.of(), null, new UsoDelModelo(10, 5, 0));
	}

	@Test
	void corta_a_las_ocho_vueltas_y_responde_con_lo_que_tiene() {
		// El guion repite su ultimo turno: el modelo nunca deja de pedir herramientas.
		ProveedorDeAgenteDePrueba modelo = new ProveedorDeAgenteDePrueba(Map.of("busca",
				List.of(pide("buscar_persona", "{\"nombre\":\"Pérez\"}"))));

		RespuestaDelAsistente r = agente(modelo, null).responder(ACTOR, CTX, "busca a Pérez", List.of(), null);

		assertThat(llamadasABuscar).hasValue(8);
		assertThat(r.herramientas()).hasSize(8);
		assertThat(r.texto()).startsWith(AgenteDelAsistente.TOPE);
		assertThat(r.advertencias()).contains(AgenteDelAsistente.AVISO_TOPE);
		assertThat(auditados).singleElement().satisfies(e -> {
			assertThat(e.details()).containsEntry("vueltas", "8").containsEntry("tokensEntrada", "80");
			assertThat(e.details().values()).noneMatch(v -> v.contains("Pérez"));
		});
	}

	@Test
	void si_el_principal_no_responde_sigue_el_alternativo() {
		ProveedorDeAgente caido = caido();
		ProveedorDeAgenteDePrueba alternativo = new ProveedorDeAgenteDePrueba(Map.of("cuantos",
				List.of(final_("Hoy hay 3 turnos."))));

		RespuestaDelAsistente r = agente(caido, alternativo).responder(ACTOR, CTX, "cuantos turnos hay hoy",
				List.of(), null);

		assertThat(r.resultado()).isEqualTo(Resultado.RESPONDIDA);
		assertThat(r.texto()).isEqualTo("Hoy hay 3 turnos.");
		assertThat(auditados.getFirst().details()).containsEntry("proveedor", "prueba");
	}

	@Test
	void sin_alternativo_el_principal_caido_es_503() {
		assertThatThrownBy(() -> agente(caido(), null).responder(ACTOR, CTX, "cuantos turnos hay hoy", List.of(), null))
				.isInstanceOf(ModeloNoDisponibleException.class);
		assertThat(auditados.getFirst().details()).containsEntry("resultado", "ERROR");
	}

	@Test
	void sin_ningun_proveedor_es_503() {
		assertThatThrownBy(() -> agente(null, null).responder(ACTOR, CTX, "hola", List.of(), null))
				.isInstanceOf(ModeloNoDisponibleException.class);
	}

	@Test
	void pedir_aclaracion_devuelve_las_opciones_que_trajo_una_herramienta() {
		ProveedorDeAgenteDePrueba modelo = new ProveedorDeAgenteDePrueba(Map.of("vino", List.of(
				pide("buscar_persona", "{\"nombre\":\"Pérez\"}"),
				// El 99 no lo devolvio ninguna herramienta: no se ofrece.
				pide(AgenteDelAsistente.PEDIR_ACLARACION,
						"{\"pregunta\":\"¿Juan o Juana Pérez?\",\"personaIds\":[1,2,99]}"))));

		RespuestaDelAsistente r = agente(modelo, null).responder(ACTOR, CTX, "vino Pérez hoy?", List.of(), null);

		assertThat(r.resultado()).isEqualTo(Resultado.ACLARACION);
		assertThat(r.texto()).isEqualTo("¿Juan o Juana Pérez?");
		assertThat(r.opciones()).extracting(RespuestaDelAsistente.OpcionDePersona::personaId).containsExactly(1L, 2L);
		assertThat(r.opciones().getFirst().etiqueta()).isEqualTo("Pérez, Juan · ***456");
		assertThat(r.herramientas()).extracting(LlamadaRegistrada::herramienta)
				.containsExactly("buscar_persona", AgenteDelAsistente.PEDIR_ACLARACION);
	}

	private static ProveedorDeAgente caido() {
		return new ProveedorDeAgente() {
			@Override
			public String nombre() {
				return "claude";
			}

			@Override
			public TurnoDelAgente responder(com.akine.platform.spi.modelo.agente.PedidoAlAgente pedido) {
				throw new com.akine.platform.spi.modelo.ModeloNoDisponibleException();
			}
		};
	}
}
