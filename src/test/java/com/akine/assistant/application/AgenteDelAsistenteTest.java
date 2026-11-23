package com.akine.assistant.application;

import com.akine.assistant.application.RespuestaDelAsistente.Resultado;
import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.assistant.spi.TipoDeAccion;
import com.akine.organization.spi.DenialKind;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.platform.infrastructure.modelo.agente.ProveedorDeAgenteDePrueba;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.modelo.agente.LlamadaAHerramienta;
import com.akine.platform.spi.modelo.agente.ProveedorDeAgente;
import com.akine.platform.spi.modelo.agente.TurnoDelAgente;
import com.akine.platform.spi.modelo.agente.UsoDelModelo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

	/** Rechaza la entrada como lo haria la validacion propia de una herramienta. */
	private final HerramientaDelAgente agendaDelDia = new HerramientaDelAgente() {
		@Override
		public String nombre() {
			return "agenda_del_dia";
		}

		@Override
		public String descripcion() {
			return "Turnos de la sede en una fecha";
		}

		@Override
		public String esquemaEntrada() {
			return "{\"type\":\"object\",\"properties\":{\"fecha\":{\"type\":\"string\"}},\"additionalProperties\":false}";
		}

		@Override
		public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
				ContextoDeConsulta ctx) {
			throw new IllegalArgumentException("La fecha tiene que ser YYYY-MM-DD o una palabra como \"hoy\".");
		}
	};

	private AgenteDelAsistente agente(ProveedorDeAgente principal, ProveedorDeAgente alternativo) {
		return new AgenteDelAsistente(new ProveedoresDelAgente(principal, alternativo),
				new CatalogoDeHerramientas(List.of(buscarPersona, agendaDelDia)),
				new AuditoriaDelAsistente(auditados::add), "SYSTEM", 8, Duration.ofSeconds(30), Clock.systemUTC());
	}

	/** Devuelve los turnos en orden y, cuando se acaban, deja de responder; guarda cada pedido. */
	private static final class ModeloGrabado implements ProveedorDeAgente {

		final List<com.akine.platform.spi.modelo.agente.PedidoAlAgente> pedidos = new ArrayList<>();
		private final List<TurnoDelAgente> turnos;

		ModeloGrabado(TurnoDelAgente... turnos) {
			this.turnos = List.of(turnos);
		}

		@Override
		public String nombre() {
			return "grabado";
		}

		@Override
		public TurnoDelAgente responder(com.akine.platform.spi.modelo.agente.PedidoAlAgente pedido) {
			pedidos.add(pedido);
			if (pedidos.size() > turnos.size()) {
				throw new com.akine.platform.spi.modelo.ModeloNoDisponibleException();
			}
			return turnos.get(pedidos.size() - 1);
		}
	}

	@Test
	void si_el_modelo_se_cae_despues_de_buscar_responde_con_lo_que_hay_y_no_con_503() {
		ModeloGrabado modelo = new ModeloGrabado(pide("buscar_persona", "{\"nombre\":\"Pérez\"}"));

		RespuestaDelAsistente r = agente(modelo, null).responder(ACTOR, CTX, "vino Pérez?", List.of(), null);

		assertThat(r.resultado()).isEqualTo(Resultado.RESPONDIDA);
		assertThat(r.texto()).startsWith(AgenteDelAsistente.TOPE);
		assertThat(r.advertencias()).contains(AgenteDelAsistente.AVISO_TOPE);
		assertThat(r.herramientas()).extracting(LlamadaRegistrada::herramienta).containsExactly("buscar_persona");
		assertThat(auditados.getFirst().details()).containsEntry("resultado", "RESPONDIDA");
	}

	@Test
	void el_mensaje_de_validacion_de_una_herramienta_vuelve_al_modelo_para_que_corrija() {
		ModeloGrabado modelo = new ModeloGrabado(pide("agenda_del_dia", "{\"fecha\":\"23 de noviembre\"}"),
				final_("No pude leer la fecha."));

		RespuestaDelAsistente r = agente(modelo, null).responder(ACTOR, CTX, "quienes vienen?", List.of(), null);

		assertThat(r.texto()).isEqualTo("No pude leer la fecha.");
		var ultimo = modelo.pedidos.get(1).mensajes().getLast();
		assertThat(ultimo).isInstanceOfSatisfying(com.akine.platform.spi.modelo.agente.MensajeDelAgente.Resultados.class,
				m -> assertThat(m.resultados()).singleElement().satisfies(res -> {
					assertThat(res.error()).isTrue();
					assertThat(res.contenidoJson()).contains("La fecha tiene que ser YYYY-MM-DD");
				}));
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

	@Test
	@SuppressWarnings("unchecked")
	void proponer_accion_termina_el_turno_con_la_tarjeta() {
		PermissionEvaluator permisos = mock(PermissionEvaluator.class);
		when(permisos.evaluate(any())).thenReturn(new PermissionDecision(true, DenialKind.NONE, "ORGANIZACION", false));
		AccionesDelAsistente acciones = mock(AccionesDelAsistente.class);
		RespuestaDelAsistente.AccionParaConfirmar tarjeta = new RespuestaDelAsistente.AccionParaConfirmar(40L,
				TipoDeAccion.PAGO, "Pago de Pérez, Juan", Instant.parse("2026-11-23T15:15:00Z"), null, List.of(),
				List.of());
		when(acciones.proponerPedido(any(), any(), any())).thenReturn(new RespuestaDelAsistente(
				AccionesDelAsistente.TEXTO_PROPUESTA, Interpretacion.FUERA_DE_ALCANCE, List.of(), List.of(), null,
				List.of(), Resultado.PROPUESTA, null, tarjeta));
		HerramientaDelAgente datosPersona = new HerramientaDelAgente() {
			@Override
			public String nombre() {
				return ProponerAccionHerramienta.DATOS_PERSONA;
			}

			@Override
			public String descripcion() {
				return "Datos de una persona";
			}

			@Override
			public String esquemaEntrada() {
				return "{\"type\":\"object\"}";
			}

			@Override
			public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
					ContextoDeConsulta ctx) {
				return new ResultadoDeHerramienta(List.of(Map.of("persona_id", 1L, "nombre", "Pérez, Juan")),
						List.of(), null, false);
			}
		};
		ObjectProvider<CatalogoDeHerramientas> proveedorDelCatalogo = mock(ObjectProvider.class);
		ProponerAccionHerramienta proponer = new ProponerAccionHerramienta(
				new AsistenteAjustes(true, Duration.ofSeconds(5), false, true, Duration.ofMinutes(15)), permisos,
				acciones, proveedorDelCatalogo);
		CatalogoDeHerramientas catalogo = new CatalogoDeHerramientas(List.of(buscarPersona, datosPersona, proponer));
		when(proveedorDelCatalogo.getIfAvailable()).thenReturn(catalogo);
		ModeloGrabado modelo = new ModeloGrabado(
				pide("buscar_persona", "{\"nombre\":\"Pérez\"}"),
				pide(ProponerAccionHerramienta.NOMBRE,
						"{\"tipo\":\"PAGO\",\"persona_id\":1,\"monto\":15000,\"medio\":\"EFECTIVO\"}"));
		AgenteDelAsistente agente = new AgenteDelAsistente(new ProveedoresDelAgente(modelo, null), catalogo,
				new AuditoriaDelAsistente(auditados::add), "SYSTEM", 8, Duration.ofSeconds(30), Clock.systemUTC());

		RespuestaDelAsistente r = agente.responder(ACTOR, CTX, "Juan Pérez pagó 15000 en efectivo", List.of(), null);

		assertThat(r.resultado()).isEqualTo(Resultado.PROPUESTA);
		assertThat(r.accion()).isEqualTo(tarjeta);
		assertThat(modelo.pedidos).as("no vuelve al modelo despues de proponer").hasSize(2);
		assertThat(modelo.pedidos.getFirst().herramientas()).extracting(d -> d.nombre())
				.contains(ProponerAccionHerramienta.NOMBRE);
		assertThat(r.herramientas()).extracting(LlamadaRegistrada::herramienta)
				.containsExactly("buscar_persona", ProponerAccionHerramienta.NOMBRE);
		ArgumentCaptor<PedidoDeAccion> pedido = ArgumentCaptor.forClass(PedidoDeAccion.class);
		verify(acciones).proponerPedido(eq(ACTOR), eq(CTX.zona()), pedido.capture());
		assertThat(pedido.getValue().personaNombre()).as("el nombre sale del padron").isEqualTo("Pérez, Juan");
		assertThat(pedido.getValue().monto()).isEqualByComparingTo("15000");
		assertThat(pedido.getValue().frase()).isEqualTo("Juan Pérez pagó 15000 en efectivo");
		assertThat(auditados.getFirst().details()).containsEntry("resultado", "PROPUESTA");
	}

	@Test
	void la_aclaracion_ofrece_personas_con_persona_id_tambien_dentro_de_una_ficha() {
		HerramientaDelAgente conClavesNuevas = new HerramientaDelAgente() {
			@Override
			public String nombre() {
				return "buscar_persona";
			}

			@Override
			public String descripcion() {
				return "Busca personas";
			}

			@Override
			public String esquemaEntrada() {
				return "{\"type\":\"object\"}";
			}

			@Override
			public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
					ContextoDeConsulta ctx) {
				// Como buscar_persona y como una ficha: la seccion anida sus filas.
				return new ResultadoDeHerramienta(List.of(
						Map.of("persona_id", 1L, "nombre", "Pérez, Juan", "documento", "DNI ***456"),
						Map.of("seccion", "datos_persona", "filas",
								List.of(Map.of("persona_id", 2L, "nombre", "Pérez, Juana")))),
						List.of(), null, false);
			}
		};
		ModeloGrabado modelo = new ModeloGrabado(
				pide("buscar_persona", "{\"nombre\":\"Pérez\"}"),
				pide(AgenteDelAsistente.PEDIR_ACLARACION,
						"{\"pregunta\":\"¿Juan o Juana Pérez?\",\"personaIds\":[1,2]}"));
		AgenteDelAsistente agente = new AgenteDelAsistente(new ProveedoresDelAgente(modelo, null),
				new CatalogoDeHerramientas(List.of(conClavesNuevas)), new AuditoriaDelAsistente(auditados::add),
				"SYSTEM", 8, Duration.ofSeconds(30), Clock.systemUTC());

		RespuestaDelAsistente r = agente.responder(ACTOR, CTX, "vino Pérez hoy?", List.of(), null);

		assertThat(r.resultado()).isEqualTo(Resultado.ACLARACION);
		assertThat(r.opciones()).extracting(RespuestaDelAsistente.OpcionDePersona::etiqueta)
				.containsExactly("Pérez, Juan · DNI ***456", "Pérez, Juana");
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
