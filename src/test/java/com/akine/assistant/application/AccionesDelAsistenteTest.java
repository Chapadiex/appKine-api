package com.akine.assistant.application;

import com.akine.assistant.application.RespuestaDelAsistente.Resultado;
import com.akine.assistant.domain.AccionPropuesta;
import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.exception.AccionInvalidaException;
import com.akine.assistant.domain.exception.AccionNoEncontradaException;
import com.akine.assistant.domain.exception.AccionNoVigenteException;
import com.akine.assistant.domain.exception.AccionesDeshabilitadasException;
import com.akine.assistant.domain.port.AccionesPropuestas;
import com.akine.assistant.spi.AccionDelAsistente;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.HerramientaDelAsistente;
import com.akine.assistant.spi.Intencion;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.PedidoDeHerramienta;
import com.akine.assistant.spi.PropuestaDeAccion;
import com.akine.assistant.spi.ResultadoDeAccion;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.TarjetaDeLlegada;
import com.akine.assistant.spi.Tarjeta.TarjetaDePago;
import com.akine.assistant.spi.TipoDeAccion;
import com.akine.organization.spi.DenialKind;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.modelo.ModeloSoloJson;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;

class AccionesDelAsistenteTest {

	private static final long ORG = 7L;
	private static final long SEDE = 3L;
	private static final ZoneId ZONA = ZoneId.of("America/Argentina/Cordoba");
	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, ORG, SEDE, "ADMINISTRATIVO");
	private static final String PAGO_DE_JUAN = "{\"accion\":\"PAGO\",\"persona\":\"Juan\",\"monto\":\"15000\"}";

	private final Reloj reloj = new Reloj();
	private final EnMemoria propuestas = new EnMemoria();
	private final PermissionEvaluator permisos = mock(PermissionEvaluator.class);
	private final Set<String> negados = new HashSet<>();
	private final List<PedidoDeAccion> pedidos = new ArrayList<>();
	private final List<Tarjeta> ejecutadas = new ArrayList<>();
	private String respuestaDelModelo = PAGO_DE_JUAN;
	private int llamadasAlModelo;
	private ResultadoDeAccion resultado = ResultadoDeAccion.confirmada("Listo.", "/pacientes/5", Map.of("cobroId", 90L));
	private RuntimeException falla;

	private AccionesDelAsistente servicio(boolean acciones) {
		// doAnswer y no when(...): el test arma mas de un servicio y re-stubear invocaria la respuesta con null.
		doAnswer(i -> {
			PermissionQuery q = i.getArgument(0);
			return negados.contains(q.permissionCode())
					? new PermissionDecision(false, DenialKind.values()[1], null, false)
					: new PermissionDecision(true, DenialKind.NONE, "ORGANIZACION", false);
		}).when(permisos).evaluate(any());
		ModeloSoloJson modelo = (mensajes, esquema, plazo) -> {
			llamadasAlModelo++;
			return respuestaDelModelo;
		};
		HerramientaDelAsistente personas = new HerramientaDelAsistente() {
			@Override
			public Intencion intencion() {
				return Intencion.BUSCAR_PERSONA;
			}

			@Override
			public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, PedidoDeHerramienta pedido) {
				return new ResultadoDeHerramienta(List.of(Map.of(ClavesDeFila.PERSONA_ID, 5L,
						ClavesDeFila.NOMBRE, "Pérez, Juan")), List.of(), null, false);
			}
		};
		return new AccionesDelAsistente(new AsistenteAjustes(true, Duration.ofSeconds(5), false, acciones,
				Duration.ofMinutes(15)), permisos, (org, f) -> true,
				new InterpretadorDeAcciones(() -> modelo, "PROMPT", Duration.ofSeconds(5)), propuestas,
				mock(AuditoriaDelAsistente.class), List.of(accion(TipoDeAccion.PAGO), accion(TipoDeAccion.LLEGADA),
						accion(TipoDeAccion.ATENCION)), List.of(personas), reloj);
	}

	private AccionDelAsistente accion(TipoDeAccion tipo) {
		return new AccionDelAsistente() {
			@Override
			public TipoDeAccion tipo() {
				return tipo;
			}

			@Override
			public PropuestaDeAccion preparar(ActorDelAsistente actor, PedidoDeAccion pedido) {
				pedidos.add(pedido);
				Tarjeta tarjeta = tipo == TipoDeAccion.PAGO ? pago(pedido.personaId())
						: new TarjetaDeLlegada(pedido.personaId(), pedido.personaNombre(), 40L, Instant.now(reloj));
				return new PropuestaDeAccion("Pago", tarjeta, Map.of(PropuestaDeAccion.PERSONA, pedido.personaId()),
						List.of(), List.of(), null);
			}

			@Override
			public ResultadoDeAccion ejecutar(ActorDelAsistente actor, long accionId, Tarjeta confirmada) {
				ejecutadas.add(confirmada);
				if (falla != null) {
					throw falla;
				}
				return resultado;
			}
		};
	}

	private static TarjetaDePago pago(long personaId) {
		return new TarjetaDePago(personaId, "Pérez, Juan", new BigDecimal("15000"), "EFECTIVO", "ARS", List.of(),
				new BigDecimal("15000"), true);
	}

	private long proponer(AccionesDelAsistente servicio) {
		return servicio.proponer(ACTOR, ZONA, "Juan pagó 15000 en efectivo", null).orElseThrow().accion().id();
	}

	@Test
	void con_las_acciones_apagadas_no_se_llama_al_modelo() {
		assertThat(servicio(false).proponer(ACTOR, ZONA, "Juan pagó 15000", null)).isEmpty();
		assertThat(llamadasAlModelo).isZero();
	}

	@Test
	void una_pregunta_o_quien_no_tiene_el_permiso_no_llegan_al_modelo() {
		assertThat(servicio(true).proponer(ACTOR, ZONA, "¿Juan pagó?", null)).isEmpty();
		negados.add("asistente:acciones");
		assertThat(servicio(true).proponer(ACTOR, ZONA, "Juan pagó 15000", null)).isEmpty();
		assertThat(llamadasAlModelo).isZero();
	}

	@Test
	void una_frase_que_no_es_accion_sigue_a_las_consultas() {
		respuestaDelModelo = "{\"accion\":\"NINGUNA\"}";
		assertThat(servicio(true).proponer(ACTOR, ZONA, "turnos de hoy por favor", null)).isEmpty();
		assertThat(propuestas.filas).isEmpty();
	}

	@Test
	void propone_sin_grabar_y_guarda_solo_los_ids() {
		RespuestaDelAsistente respuesta = servicio(true).proponer(ACTOR, ZONA, "Juan pagó 15000 en efectivo", null)
				.orElseThrow();

		assertThat(respuesta.resultado()).isEqualTo(Resultado.PROPUESTA);
		assertThat(respuesta.accion().tipo()).isEqualTo(TipoDeAccion.PAGO);
		assertThat(respuesta.accion().venceEn()).isEqualTo(Instant.now(reloj).plus(Duration.ofMinutes(15)));
		assertThat(pedidos.getFirst().monto()).isEqualByComparingTo("15000");
		assertThat(pedidos.getFirst().medio()).isEqualTo("EFECTIVO");
		assertThat(propuestas.filas.get(respuesta.accion().id()).referencias()).containsExactly(Map.entry("personaId", 5L));
		assertThat(ejecutadas).isEmpty();
	}

	@Test
	void quien_no_puede_registrar_sesiones_y_cuenta_una_atencion_propone_la_llegada() {
		respuestaDelModelo = "{\"accion\":\"ATENCION\",\"persona\":\"Juan\",\"practicas\":[\"ultrasonido\"]}";
		negados.add("sesion:register");

		RespuestaDelAsistente respuesta = servicio(true).proponer(ACTOR, ZONA, "hoy vino Juan e hizo ultrasonido", null)
				.orElseThrow();

		assertThat(respuesta.accion().tipo()).isEqualTo(TipoDeAccion.LLEGADA);
		assertThat(pedidos.getFirst().practicas()).containsExactly("ultrasonido");
	}

	@Test
	void confirmar_dos_veces_ejecuta_una_sola_y_devuelve_lo_mismo() {
		AccionesDelAsistente servicio = servicio(true);
		long id = proponer(servicio);

		ResultadoDeAccion primera = servicio.confirmar(ACTOR, SEDE, id, pago(5L));
		ResultadoDeAccion segunda = servicio.confirmar(ACTOR, SEDE, id, pago(5L));

		assertThat(ejecutadas).hasSize(1);
		assertThat(segunda.fueConfirmada()).isTrue();
		assertThat(segunda.enlace()).isEqualTo(primera.enlace());
		assertThat(segunda.referencias()).isEqualTo(primera.referencias());
		assertThat(segunda.texto()).isEqualTo(AccionesDelAsistente.YA_CONFIRMADA);
		// La fila guarda solo ids: el texto de la accion nombra a la persona (§5.4).
		assertThat(propuestas.filas.get(id).resultado()).doesNotContain("Listo");
		assertThat(propuestas.filas.get(id).estado()).isEqualTo(AccionPropuesta.Estado.CONFIRMADA);
	}

	@Test
	void mientras_otro_pedido_la_confirma_responde_que_no_esta_vigente() {
		AccionesDelAsistente servicio = servicio(true);
		long id = proponer(servicio);
		propuestas.tomar(id, Instant.now(reloj));

		assertThatThrownBy(() -> servicio.confirmar(ACTOR, SEDE, id, pago(5L)))
				.isInstanceOf(AccionNoVigenteException.class);
		assertThat(ejecutadas).isEmpty();
	}

	@Test
	void una_tarjeta_de_otra_persona_o_de_otro_tipo_no_se_ejecuta() {
		AccionesDelAsistente servicio = servicio(true);
		long id = proponer(servicio);

		assertThatThrownBy(() -> servicio.confirmar(ACTOR, SEDE, id, pago(6L)))
				.isInstanceOf(AccionInvalidaException.class);
		assertThatThrownBy(() -> servicio.confirmar(ACTOR, SEDE, id, new TarjetaDeLlegada(5L, "x", 40L, null)))
				.isInstanceOf(AccionInvalidaException.class);
		assertThat(ejecutadas).isEmpty();
	}

	@Test
	void vencida_no_se_confirma() {
		AccionesDelAsistente servicio = servicio(true);
		long id = proponer(servicio);
		reloj.ahora = reloj.ahora.plus(Duration.ofMinutes(16));

		assertThatThrownBy(() -> servicio.confirmar(ACTOR, SEDE, id, pago(5L)))
				.isInstanceOf(AccionNoVigenteException.class);
		assertThat(ejecutadas).isEmpty();
	}

	@Test
	void la_de_otra_cuenta_o_de_otra_sede_no_existe() {
		AccionesDelAsistente servicio = servicio(true);
		long id = proponer(servicio);

		assertThatThrownBy(() -> servicio.confirmar(new ActorDelAsistente(12L, false, ORG, SEDE, "ADMINISTRATIVO"),
				SEDE, id, pago(5L))).isInstanceOf(AccionNoEncontradaException.class);
		assertThatThrownBy(() -> servicio.confirmar(ACTOR, 4L, id, pago(5L)))
				.isInstanceOf(AccionNoEncontradaException.class);
	}

	@Test
	void un_rechazo_o_la_falta_de_permiso_dejan_la_propuesta_abierta() {
		AccionesDelAsistente servicio = servicio(true);
		long id = proponer(servicio);
		resultado = ResultadoDeAccion.rechazada("La caja está cerrada.");

		assertThat(servicio.confirmar(ACTOR, SEDE, id, pago(5L)).fueConfirmada()).isFalse();
		assertThat(propuestas.filas.get(id).estado()).isEqualTo(AccionPropuesta.Estado.PROPUESTA);

		falla = new AccessDeniedException("cobro:register");
		ResultadoDeAccion sinPermiso = servicio.confirmar(ACTOR, SEDE, id, pago(5L));
		assertThat(sinPermiso.fueConfirmada()).isFalse();
		assertThat(sinPermiso.texto()).isEqualTo(AccionesDelAsistente.SIN_PERMISO);

		falla = null;
		resultado = ResultadoDeAccion.confirmada("Listo.", null, Map.of());
		assertThat(servicio.confirmar(ACTOR, SEDE, id, pago(5L)).fueConfirmada()).isTrue();
		assertThat(ejecutadas).hasSize(3);
	}

	@Test
	void una_excepcion_al_ejecutar_suelta_la_propuesta_y_se_propaga() {
		AccionesDelAsistente servicio = servicio(true);
		long id = proponer(servicio);
		falla = new IllegalStateException("base caida");

		assertThatThrownBy(() -> servicio.confirmar(ACTOR, SEDE, id, pago(5L)))
				.isInstanceOf(IllegalStateException.class);
		assertThat(propuestas.filas.get(id).estado()).isEqualTo(AccionPropuesta.Estado.PROPUESTA);
	}

	@Test
	void descartada_no_se_confirma_y_apagadas_responden_409() {
		AccionesDelAsistente servicio = servicio(true);
		long id = proponer(servicio);
		servicio.descartar(ACTOR, SEDE, id);

		assertThatThrownBy(() -> servicio.confirmar(ACTOR, SEDE, id, pago(5L)))
				.isInstanceOf(AccionNoVigenteException.class);
		assertThatThrownBy(() -> servicio(false).confirmar(ACTOR, SEDE, id, pago(5L)))
				.isInstanceOf(AccionesDeshabilitadasException.class);
	}

	/** Un reloj que el test adelanta. */
	private static final class Reloj extends Clock {
		private Instant ahora = Instant.parse("2026-11-19T15:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(ahora, zone);
		}

		@Override
		public Instant instant() {
			return ahora;
		}
	}

	/** La tabla, con las mismas condiciones que los UPDATE de {@code JdbcAccionesPropuestas}. */
	private static final class EnMemoria implements AccionesPropuestas {
		private final Map<Long, AccionPropuesta> filas = new HashMap<>();
		private long proximo = 1;

		@Override
		public long guardar(AccionPropuesta n) {
			long id = proximo++;
			filas.put(id, con(n, id, AccionPropuesta.Estado.PROPUESTA, null));
			return id;
		}

		@Override
		public Optional<AccionPropuesta> buscar(long id, long organizationId, long consultorioId, long cuentaId) {
			return Optional.ofNullable(filas.get(id)).filter(f -> f.organizationId() == organizationId
					&& f.consultorioId() == consultorioId && f.cuentaId() == cuentaId);
		}

		@Override
		public boolean tomar(long id, Instant ahora) {
			AccionPropuesta f = filas.get(id);
			if (f.estado() != AccionPropuesta.Estado.PROPUESTA || f.vencida(ahora)) {
				return false;
			}
			filas.put(id, con(f, id, AccionPropuesta.Estado.EN_CURSO, null));
			return true;
		}

		@Override
		public void soltar(long id) {
			filas.put(id, con(filas.get(id), id, AccionPropuesta.Estado.PROPUESTA, null));
		}

		@Override
		public void confirmar(long id, String resultadoJson, Instant ahora) {
			filas.put(id, con(filas.get(id), id, AccionPropuesta.Estado.CONFIRMADA, resultadoJson));
		}

		@Override
		public boolean descartar(long id, Instant ahora) {
			AccionPropuesta f = filas.get(id);
			if (f.estado() != AccionPropuesta.Estado.PROPUESTA) {
				return false;
			}
			filas.put(id, con(f, id, AccionPropuesta.Estado.DESCARTADA, null));
			return true;
		}

		private static AccionPropuesta con(AccionPropuesta f, long id, AccionPropuesta.Estado estado, String resultado) {
			return new AccionPropuesta(id, f.organizationId(), f.consultorioId(), f.cuentaId(), f.tipo(), estado,
					f.referencias(), resultado, f.creadaEn(), f.venceEn());
		}
	}
}
