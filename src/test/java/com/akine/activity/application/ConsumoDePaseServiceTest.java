package com.akine.activity.application;

import com.akine.activity.application.ConsumoDeCredito.Efecto;
import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.AsistenciaEvento;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.TipoMovimientoPase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.NetoDeAsistencia;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.offering.spi.EsquemaDeCobro;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Las decisiones del consumo de creditos por asistencia (RF-M29-003, RN-M29-005). Lo que un mock
 * NO puede contestar —que dos transacciones no consuman el ultimo credito— lo contesta
 * {@code ConsumoDePaseConcurrenteIT} contra MySQL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConsumoDePaseService")
class ConsumoDePaseServiceTest {

	private static final long ORG = 1L;
	private static final long SEDE = 10L;
	private static final long OFERTA = 45L;
	private static final long PERSONA = 512L;
	private static final long ASISTENCIA = 900L;
	private static final long EVENTO = 4000L;
	private static final long CUENTA = 99L;
	private static final Instant INICIO = Instant.parse("2026-10-14T13:00:00Z");

	@Mock private PaseServicioRepositoryPort pases;
	@Mock private MovimientoPaseRepositoryPort movimientos;
	@Mock private OfertaDirectory ofertas;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private AuditTrail auditTrail;

	private ConsumoDePaseService service;

	@BeforeEach
	void setUp() {
		service = new ConsumoDePaseService(pases, movimientos, ofertas, consultorios, auditTrail);
		given(ofertas.esquemaDeCobro(ORG, SEDE, OFERTA)).willReturn(Optional.of(EsquemaDeCobro.POR_PACK));
		given(movimientos.netoDeAsistencia(ORG, ASISTENCIA)).willReturn(List.of());
	}

	@Test
	@DisplayName("Presente en una oferta POR_PACK consume del pase que vence antes, con el saldo releido")
	void consume_el_primer_elegible() {
		given(pases.lockElegibles(eq(ORG), eq(PERSONA), eq(OFERTA), any())).willReturn(List.of(7L, 8L));
		given(pases.consumirUno(eq(ORG), eq(7L), any())).willReturn(1);
		given(pases.saldoActual(ORG, 7L)).willReturn(5);

		ConsumoDeCredito consumo = aplicar(ResultadoAsistencia.PRESENTE, null);

		assertThat(consumo).isEqualTo(new ConsumoDeCredito(Efecto.CONSUMIDO, 7L, 5));
		ArgumentCaptor<MovimientoPase> movimiento = ArgumentCaptor.forClass(MovimientoPase.class);
		verify(movimientos).registrar(movimiento.capture());
		assertThat(movimiento.getValue().getTipo()).isEqualTo(TipoMovimientoPase.CONSUMO);
		assertThat(movimiento.getValue().getCantidad()).isEqualTo(-1);
		assertThat(movimiento.getValue().getSaldoResultante()).isEqualTo(5);
		assertThat(movimiento.getValue().getOrigenId()).isEqualTo(EVENTO);
		assertThat(movimiento.getValue().getAsistenciaId()).isEqualTo(ASISTENCIA);
		verify(pases, never()).consumirUno(eq(ORG), eq(8L), any());
	}

	@Test
	@DisplayName("La fecha de vigencia sale del inicio de la clase, no del reloj")
	void dia_de_la_clase_en_la_zona_de_la_sede() {
		// 01:30 UTC del 15 es todavia el 14 en Cordoba.
		ClaseProgramada clase = clase(Instant.parse("2026-10-15T01:30:00Z"));
		given(consultorios.find(ORG, SEDE)).willReturn(Optional.empty());
		given(pases.lockElegibles(anyLong(), anyLong(), anyLong(), any())).willReturn(List.of());

		service.aplicar(clase, asistencia(ResultadoAsistencia.PRESENTE), evento(), CUENTA, null, Instant.now());

		// Sin sede resuelta ZonaDeSede cae a UTC: el 15. Con sede en Cordoba seria el 14; lo que
		// importa aca es que la fecha sale del inicio de la clase, no del reloj.
		verify(pases).lockElegibles(ORG, PERSONA, OFERTA, LocalDate.of(2026, 10, 15));
	}

	@Test
	@DisplayName("Si el primer candidato se agoto en carrera, prueba el siguiente")
	void prueba_el_siguiente_si_el_update_no_toca_filas() {
		given(pases.lockElegibles(eq(ORG), eq(PERSONA), eq(OFERTA), any())).willReturn(List.of(7L, 8L));
		given(pases.consumirUno(eq(ORG), eq(7L), any())).willReturn(0);
		given(pases.consumirUno(eq(ORG), eq(8L), any())).willReturn(1);
		given(pases.saldoActual(ORG, 8L)).willReturn(3);

		assertThat(aplicar(ResultadoAsistencia.PRESENTE_TARDE, null))
				.isEqualTo(new ConsumoDeCredito(Efecto.CONSUMIDO, 8L, 3));
	}

	@Test
	@DisplayName("Sin pase elegible no escribe ledger, informa y audita")
	void sin_pase_elegible() {
		given(pases.lockElegibles(anyLong(), anyLong(), anyLong(), any())).willReturn(List.of());

		ConsumoDeCredito consumo = aplicar(ResultadoAsistencia.PRESENTE, null);

		assertThat(consumo.efecto()).isEqualTo(Efecto.SIN_PASE_ELEGIBLE);
		verify(movimientos, never()).registrar(any());
		verify(auditTrail).record(any());
	}

	@Test
	@DisplayName("Una oferta que no cobra por pack no mueve creditos")
	void oferta_que_no_es_por_pack() {
		given(ofertas.esquemaDeCobro(ORG, SEDE, OFERTA)).willReturn(Optional.of(EsquemaDeCobro.POR_SESION));

		assertThat(aplicar(ResultadoAsistencia.PRESENTE, null).efecto()).isEqualTo(Efecto.NO_APLICA);
		verify(pases, never()).lockElegibles(anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("Ausente sin credito tomado no hace nada y ni siquiera mira la oferta")
	void ausente_sin_credito() {
		assertThat(aplicar(ResultadoAsistencia.AUSENTE, null).efecto()).isEqualTo(Efecto.NO_APLICA);
		verify(ofertas, never()).esquemaDeCobro(anyLong(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("Corregir a ausente devuelve el credito tomado al mismo pase, con el motivo")
	void correccion_a_ausente_devuelve() {
		given(movimientos.netoDeAsistencia(ORG, ASISTENCIA)).willReturn(List.of(new NetoDeAsistencia(7L, -1)));
		given(pases.devolverUno(ORG, 7L)).willReturn(1);
		given(pases.saldoActual(ORG, 7L)).willReturn(6);

		ConsumoDeCredito consumo = aplicar(ResultadoAsistencia.AUSENTE, "Se marco por error");

		assertThat(consumo).isEqualTo(new ConsumoDeCredito(Efecto.DEVUELTO, 7L, 6));
		ArgumentCaptor<MovimientoPase> movimiento = ArgumentCaptor.forClass(MovimientoPase.class);
		verify(movimientos).registrar(movimiento.capture());
		assertThat(movimiento.getValue().getTipo()).isEqualTo(TipoMovimientoPase.DEVOLUCION);
		assertThat(movimiento.getValue().getCantidad()).isEqualTo(1);
		assertThat(movimiento.getValue().getMotivo()).isEqualTo("Se marco por error");
	}

	@Test
	@DisplayName("Presente con el credito ya tomado no consume otra vez")
	void ya_tomado_no_consume() {
		given(movimientos.netoDeAsistencia(ORG, ASISTENCIA)).willReturn(List.of(new NetoDeAsistencia(7L, -1)));
		given(pases.saldoActual(ORG, 7L)).willReturn(6);

		assertThat(aplicar(ResultadoAsistencia.PRESENTE, null))
				.isEqualTo(new ConsumoDeCredito(Efecto.SIN_CAMBIO, 7L, 6));
		verify(pases, never()).consumirUno(anyLong(), anyLong(), any());
		verify(movimientos, never()).registrar(any());
	}

	@Test
	@DisplayName("Si la devolucion no toca filas no inventa un movimiento")
	void devolucion_imposible_no_inventa() {
		given(movimientos.netoDeAsistencia(ORG, ASISTENCIA)).willReturn(List.of(new NetoDeAsistencia(7L, -1)));
		given(pases.devolverUno(ORG, 7L)).willReturn(0);

		assertThatThrownBy(() -> aplicar(ResultadoAsistencia.AUSENTE, "x"))
				.isInstanceOf(IllegalStateException.class);
		verify(movimientos, never()).registrar(any());
	}

	// =================================================================================

	private ConsumoDeCredito aplicar(ResultadoAsistencia resultado, String motivo) {
		return service.aplicar(clase(INICIO), asistencia(resultado), evento(), CUENTA, motivo, Instant.now());
	}

	private static ClaseProgramada clase(Instant inicio) {
		ClaseProgramada clase = new ClaseProgramada(
				ORG, SEDE, OFERTA, 31L, 8L, "Pilates", inicio, inicio.plus(1, ChronoUnit.HOURS), 8, CUENTA,
				Instant.now(), null, null);
		ReflectionTestUtils.setField(clase, "id", 77L);
		return clase;
	}

	private static AsistenciaActividad asistencia(ResultadoAsistencia resultado) {
		InscripcionClase inscripcion = InscripcionClase.conLugar(
				ORG, SEDE, 77L, PERSONA, CUENTA, Instant.now(), null, null);
		ReflectionTestUtils.setField(inscripcion, "id", 412L);
		AsistenciaActividad asistencia = AsistenciaActividad.registrar(
				inscripcion, resultado, OrigenAsistencia.MOSTRADOR, 31L, null, CUENTA, Instant.now());
		ReflectionTestUtils.setField(asistencia, "id", ASISTENCIA);
		return asistencia;
	}

	private static AsistenciaEvento evento() {
		AsistenciaEvento evento = org.mockito.Mockito.mock(AsistenciaEvento.class);
		given(evento.getId()).willReturn(EVENTO);
		return evento;
	}
}
