package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.billing.spi.DevengoDeClases;
import com.akine.billing.spi.DevengoDeClases.ClaseAsistida;
import com.akine.billing.spi.DevengoDeClases.DeudaDeClase;
import com.akine.offering.spi.EsquemaDeCobro;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.organization.spi.ConsultorioSnapshot;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las transiciones del devengo por clase (U-39, RF-M18-008). Lo que el motor contesta —el unique de
 * la deuda viva, el FOR UPDATE, el cobro— lo prueba {@code DevengoPorClaseIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DevengoPorClaseService")
class DevengoPorClaseServiceTest {

	private static final long ORG = 1L;
	private static final long SEDE = 10L;
	private static final long OFERTA = 45L;
	private static final long ASISTENCIA = 900L;
	private static final long CUENTA = 99L;
	// 01:00 UTC del 15/10 es el 14/10 en Cordoba: el precio se pide para el dia de la SEDE.
	private static final Instant INICIO = Instant.parse("2026-10-15T01:00:00Z");
	private static final LocalDate DIA_EN_LA_SEDE = LocalDate.parse("2026-10-14");

	@Mock private DevengoDeClases devengo;
	@Mock private OfertaDirectory ofertas;
	@Mock private AuditTrail auditTrail;

	private DevengoPorClaseService service;

	@BeforeEach
	void setUp() {
		service = new DevengoPorClaseService(devengo, ofertas, auditTrail);
		given(ofertas.esquemaDeCobro(ORG, SEDE, OFERTA)).willReturn(Optional.of(EsquemaDeCobro.POR_CLASE));
		given(ofertas.precioVigenteEl(ORG, SEDE, OFERTA, DIA_EN_LA_SEDE)).willReturn(
				Optional.of(new PrecioDeOferta(OFERTA, new BigDecimal("6000.00"), "ARS")));
		given(devengo.devengar(any(), anyBoolean())).willReturn(new DeudaDeClase(
				DeudaDeClase.Efecto.DEVENGADA, 5L, new BigDecimal("6000.00"), new BigDecimal("6000.00"), "ARS",
				"PENDIENTE"));
	}

	@Test
	@DisplayName("Alta presente devenga al precio del dia de la clase en la sede, sin buscar una previa")
	void alta_presente_devenga() {
		DevengoDeClase resultado = aplicar(ResultadoAsistencia.PRESENTE, null);

		assertThat(resultado.efecto()).isEqualTo(DevengoDeClase.Efecto.DEVENGADA);
		ArgumentCaptor<ClaseAsistida> asistida = ArgumentCaptor.forClass(ClaseAsistida.class);
		verify(devengo).devengar(asistida.capture(), eq(false));
		assertThat(asistida.getValue().importe()).isEqualByComparingTo("6000.00");
		assertThat(asistida.getValue().asistenciaId()).isEqualTo(ASISTENCIA);
	}

	@Test
	@DisplayName("El no-show no devenga, y ni siquiera lee el esquema")
	void ausente_sin_previa_no_aplica() {
		assertThat(aplicar(ResultadoAsistencia.AUSENTE, null).efecto()).isEqualTo(DevengoDeClase.Efecto.NO_APLICA);
		verifyNoInteractions(ofertas, devengo);
	}

	@Test
	@DisplayName("Presente a presente con demora no toca la deuda (respeta una anulada a mano)")
	void presente_a_tarde_sin_cambio() {
		assertThat(aplicar(ResultadoAsistencia.PRESENTE_TARDE, ResultadoAsistencia.PRESENTE).efecto())
				.isEqualTo(DevengoDeClase.Efecto.SIN_CAMBIO);
		verifyNoInteractions(devengo);
	}

	@Test
	@DisplayName("Ausente a presente re-devenga buscando la viva; presente a ausente anula")
	void correcciones() {
		aplicar(ResultadoAsistencia.PRESENTE, ResultadoAsistencia.AUSENTE);
		verify(devengo).devengar(any(), eq(true));

		given(devengo.anular(eq(ORG), eq(ASISTENCIA), eq(CUENTA), any(), any()))
				.willReturn(DeudaDeClase.sinDeuda());
		assertThat(aplicar(ResultadoAsistencia.AUSENTE, ResultadoAsistencia.PRESENTE).efecto())
				.isEqualTo(DevengoDeClase.Efecto.SIN_DEUDA);
	}

	@Test
	@DisplayName("Una oferta que no es POR_CLASE no devenga; sin precio se informa y no devenga")
	void no_por_clase_y_sin_precio() {
		given(ofertas.esquemaDeCobro(ORG, SEDE, OFERTA)).willReturn(Optional.of(EsquemaDeCobro.POR_PACK));
		assertThat(aplicar(ResultadoAsistencia.PRESENTE, null).efecto()).isEqualTo(DevengoDeClase.Efecto.NO_APLICA);

		given(ofertas.esquemaDeCobro(ORG, SEDE, OFERTA)).willReturn(Optional.of(EsquemaDeCobro.POR_CLASE));
		given(ofertas.precioVigenteEl(anyLong(), anyLong(), anyLong(), any())).willReturn(Optional.empty());
		assertThat(aplicar(ResultadoAsistencia.PRESENTE, null).efecto()).isEqualTo(DevengoDeClase.Efecto.SIN_PRECIO);
		verify(devengo, never()).devengar(any(), anyBoolean());
		verify(auditTrail).record(any());
	}

	// =================================================================================

	private DevengoDeClase aplicar(ResultadoAsistencia resultado, ResultadoAsistencia anterior) {
		return service.aplicar(clase(), asistencia(resultado), anterior, CUENTA, "motivo", Instant.now(),
				new LecturasDeConsumo(new ConsultorioSnapshot(SEDE, ORG, "Sede", "America/Argentina/Cordoba", true)));
	}

	private static ClaseProgramada clase() {
		ClaseProgramada clase = new ClaseProgramada(
				ORG, SEDE, OFERTA, 31L, 8L, "Pilates", INICIO, INICIO.plus(1, ChronoUnit.HOURS), 8, CUENTA,
				Instant.now(), null, null);
		ReflectionTestUtils.setField(clase, "id", 77L);
		return clase;
	}

	private static AsistenciaActividad asistencia(ResultadoAsistencia resultado) {
		InscripcionClase inscripcion = InscripcionClase.conLugar(
				ORG, SEDE, 77L, 512L, CUENTA, Instant.now(), null, null);
		ReflectionTestUtils.setField(inscripcion, "id", 412L);
		AsistenciaActividad asistencia = AsistenciaActividad.registrar(
				inscripcion, resultado, OrigenAsistencia.MOSTRADOR, 31L, null, CUENTA, Instant.now());
		ReflectionTestUtils.setField(asistencia, "id", ASISTENCIA);
		return asistencia;
	}
}
