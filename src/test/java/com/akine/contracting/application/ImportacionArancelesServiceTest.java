package com.akine.contracting.application;

import com.akine.contracting.application.ImportacionAranceles.EstadoFila;
import com.akine.contracting.application.ImportacionAranceles.Fila;
import com.akine.contracting.application.ImportacionAranceles.MotivoRechazo;
import com.akine.contracting.application.ImportacionAranceles.Resultado;
import com.akine.contracting.application.ImportacionAranceles.ResultadoFila;
import com.akine.contracting.domain.Convenio;
import com.akine.contracting.domain.ConvenioArancel;
import com.akine.contracting.domain.ConvenioLock;
import com.akine.contracting.domain.ModalidadConvenio;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioArancelRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioLockRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioRepositoryPort;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PracticaDeOferta;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * La evaluacion por fila de la importacion de aranceles (B-7), sin base de datos.
 *
 * <p>Que la confirmacion sea todo o nada contra MySQL real, y que dos confirmaciones concurrentes
 * no se pisen, lo prueba {@code ImportacionArancelesIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ImportacionArancelesService")
class ImportacionArancelesServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 7L;
	private static final long SEDE_ID = 20L;
	private static final long CONVENIO_ID = 140L;
	private static final long PRACTICA_ID = 412L;
	private static final long OTRA_PRACTICA_ID = 413L;
	private static final long OFERTA_ID = 77L;
	private static final LocalDate ENERO = LocalDate.of(2027, 1, 1);
	private static final LocalDate JUNIO_30 = LocalDate.of(2027, 6, 30);
	private static final LocalDate JULIO = LocalDate.of(2027, 7, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2027, 12, 31);

	@Mock private ConvenioRepositoryPort convenios;
	@Mock private ConvenioArancelRepositoryPort aranceles;
	@Mock private ConvenioLockRepositoryPort locks;
	@Mock private ConvenioLockIniciador lockIniciador;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private CatalogoDirectory catalogo;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;
	@Mock private OfertaDirectory ofertas;
	@Mock private PracticasDeOfertaDirectory practicasDeOferta;

	private ImportacionArancelesService service;

	private final OperatingActor actor = new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);
	private final AtomicLong ids = new AtomicLong(1000);

	@BeforeEach
	void setUp() {
		service = new ImportacionArancelesService(convenios, aranceles, locks, lockIniciador,
				consultorios, catalogo, permissionGuard, auditTrail, ofertas, practicasDeOferta);

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(new ConsultorioSnapshot(
				SEDE_ID, ORG_ID, "Sede Centro", "America/Argentina/Cordoba", true)));
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(locks.lockByScope(anyLong(), anyLong()))
				.willReturn(Optional.of(Mockito.mock(ConvenioLock.class)));
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID))
				.willReturn(Optional.of(convenio()));
		given(catalogo.findPractica(any(), anyLong(), any())).willReturn(Optional.empty());
		given(catalogo.findPractica(any(), Mockito.eq(PRACTICA_ID), any()))
				.willReturn(Optional.of(practica(PRACTICA_ID, ORG_ID, "KIN-01")));
		given(catalogo.practicasVigentes(any(), any())).willReturn(List.of(
				practica(PRACTICA_ID, ORG_ID, "KIN-01"),
				practica(OTRA_PRACTICA_ID, ORG_ID, "KIN-02"),
				// Un codigo propio que repite uno global: ambiguo por codigo.
				practica(500L, ORG_ID, "DUP"),
				practica(501L, null, "dup")));
		given(aranceles.findAllByConvenio(ORG_ID, CONVENIO_ID)).willReturn(List.of());
		given(aranceles.saveAndFlush(any())).willAnswer(i -> {
			ConvenioArancel arancel = i.getArgument(0);
			ReflectionTestUtils.setField(arancel, "id", ids.incrementAndGet());
			return arancel;
		});
	}

	@Test
	@DisplayName("preview: cada fila con su desenlace, sin escribir ni tomar el lock")
	void preview_por_fila() {
		ofertaConObraSocial(false);
		given(aranceles.findAllByConvenio(ORG_ID, CONVENIO_ID))
				.willReturn(List.of(vigente(999L, PRACTICA_ID, ENERO, JUNIO_30)));

		Resultado resultado = service.previsualizar(actor, SEDE_ID, CONVENIO_ID, List.of(
				// 1: se pisa con el vigente 999
				fila(PRACTICA_ID, null, null, "12000.00", "9600.00", "2400.00", ENERO, DICIEMBRE),
				// 2: alta, por codigo, segundo semestre de la otra practica
				fila(null, "kin-02", null, "9000.00", "9000.00", "0", JULIO, DICIEMBRE),
				// 3: se pisa con la fila 2 del mismo lote
				fila(null, "KIN-02", null, "9500.00", "9500.00", "0", ENERO, DICIEMBRE),
				// 4: importes que no cuadran
				fila(PRACTICA_ID, null, null, "100.00", "90.00", "0", JULIO, DICIEMBRE),
				// 5: codigo inexistente
				fila(null, "NOPE", null, "1.00", "1.00", "0", JULIO, DICIEMBRE),
				// 6: oferta que no admite obra social
				fila(PRACTICA_ID, null, OFERTA_ID, "1.00", "1.00", "0", JULIO, DICIEMBRE),
				// 7: codigo ambiguo (propio y global)
				fila(null, "dup", null, "1.00", "1.00", "0", JULIO, DICIEMBRE),
				// 8: fuera de la vigencia del convenio
				fila(PRACTICA_ID, null, null, "1.00", "1.00", "0", JULIO, LocalDate.of(2028, 1, 1)),
				// 9: tres decimales
				fila(PRACTICA_ID, null, null, "1.005", "1.005", "0", JULIO, DICIEMBRE)));

		List<ResultadoFila> filas = resultado.filas();
		assertThat(resultado.aplicada()).isFalse();
		assertThat(filas).extracting(ResultadoFila::estado).containsExactly(
				EstadoFila.RECHAZADA, EstadoFila.ALTA, EstadoFila.RECHAZADA, EstadoFila.RECHAZADA,
				EstadoFila.RECHAZADA, EstadoFila.RECHAZADA, EstadoFila.RECHAZADA,
				EstadoFila.RECHAZADA, EstadoFila.RECHAZADA);
		assertThat(filas).extracting(ResultadoFila::motivo).containsExactly(
				MotivoRechazo.ARANCEL_SOLAPADO, null, MotivoRechazo.ARANCEL_SOLAPADO,
				MotivoRechazo.DATOS_INVALIDOS, MotivoRechazo.PRACTICA_NO_ACCESIBLE,
				MotivoRechazo.OFERTA_SIN_OBRA_SOCIAL, MotivoRechazo.PRACTICA_NO_ACCESIBLE,
				MotivoRechazo.DATOS_INVALIDOS, MotivoRechazo.DATOS_INVALIDOS);
		assertThat(filas.get(0).arancelExistenteId()).isEqualTo(999L);
		assertThat(filas.get(1).practicaId()).isEqualTo(OTRA_PRACTICA_ID);
		assertThat(filas.get(2).filaEnConflicto()).isEqualTo(2);

		verify(lockIniciador, never()).asegurar(anyLong(), anyLong());
		verify(locks, never()).lockByScope(anyLong(), anyLong());
		verify(aranceles, never()).saveAndFlush(any());
		verify(auditTrail, never()).record(any());
	}

	@Test
	@DisplayName("preview: el general y el de una oferta conviven; la oferta tiene que declarar la "
			+ "practica")
	void preview_por_oferta() {
		ofertaConObraSocial(true);
		given(aranceles.findAllByConvenio(ORG_ID, CONVENIO_ID))
				.willReturn(List.of(vigente(999L, PRACTICA_ID, ENERO, DICIEMBRE)));
		given(catalogo.findPractica(any(), Mockito.eq(OTRA_PRACTICA_ID), any()))
				.willReturn(Optional.of(practica(OTRA_PRACTICA_ID, ORG_ID, "KIN-02")));

		Resultado resultado = service.previsualizar(actor, SEDE_ID, CONVENIO_ID, List.of(
				fila(PRACTICA_ID, null, OFERTA_ID, "15000.00", "15000.00", "0", ENERO, DICIEMBRE),
				fila(OTRA_PRACTICA_ID, null, OFERTA_ID, "1.00", "1.00", "0", ENERO, DICIEMBRE)));

		assertThat(resultado.filas()).extracting(ResultadoFila::motivo)
				.containsExactly(null, MotivoRechazo.PRACTICA_NO_HABILITADA_EN_OFERTA);
	}

	@Test
	@DisplayName("confirmar con una fila que no entra: 409 con todas las filas y ninguna escrita")
	void confirmar_todo_o_nada() {
		assertThatThrownBy(() -> service.confirmar(actor, SEDE_ID, CONVENIO_ID, List.of(
				fila(PRACTICA_ID, null, null, "12000.00", "9600.00", "2400.00", ENERO, JUNIO_30),
				fila(PRACTICA_ID, null, null, "13000.00", "13000.00", "0", JUNIO_30, DICIEMBRE))))
				.isInstanceOf(ImportacionArancelesRechazadaException.class)
				.satisfies(error -> assertThat(
						((ImportacionArancelesRechazadaException) error).getFilas())
						.extracting(ResultadoFila::estado)
						.containsExactly(EstadoFila.ALTA, EstadoFila.RECHAZADA));

		verify(aranceles, never()).saveAndFlush(any());
		verify(auditTrail, never()).record(any());
	}

	@Test
	@DisplayName("confirmar: lock antes de leer, una alta por fila y un evento por lote")
	void confirmar_aplica() {
		Resultado resultado = service.confirmar(actor, SEDE_ID, CONVENIO_ID, List.of(
				fila(PRACTICA_ID, null, null, "12000.00", "9600.00", "2400.00", ENERO, JUNIO_30),
				fila(null, "KIN-02", null, "9000.00", "9000.00", "0", JULIO, DICIEMBRE)));

		assertThat(resultado.aplicada()).isTrue();
		assertThat(resultado.filas()).extracting(ResultadoFila::arancelId)
				.doesNotContainNull()
				.doesNotHaveDuplicates();

		InOrder orden = inOrder(lockIniciador, locks, aranceles);
		orden.verify(lockIniciador).asegurar(ORG_ID, SEDE_ID);
		orden.verify(locks).lockByScope(ORG_ID, SEDE_ID);
		orden.verify(aranceles).findAllByConvenio(ORG_ID, CONVENIO_ID);
		orden.verify(aranceles, times(2)).saveAndFlush(any());

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail, times(3)).record(auditoria.capture());
		assertThat(auditoria.getAllValues()).extracting(AuditEntry::eventType).containsExactly(
				"ARANCEL_CREATED", "ARANCEL_CREATED", "ARANCELES_IMPORTADOS");
		assertThat(auditoria.getAllValues().get(2).details())
				.containsEntry("cantidad", "2")
				.containsEntry("rango", ENERO + " a " + DICIEMBRE);
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private void ofertaConObraSocial(boolean admite) {
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(new OfertaSnapshot(
				OFERTA_ID, ORG_ID, SEDE_ID, 5L, "Kinesio OSDE", 45, 1, false, true, false, false,
				true, ENERO, null, true)));
		given(ofertas.precioDe(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(
				new PrecioDeOferta(OFERTA_ID, new BigDecimal("15000.00"), "ARS", admite)));
		given(practicasDeOferta.practicasHabilitadas(ORG_ID, SEDE_ID, OFERTA_ID))
				.willReturn(List.of(new PracticaDeOferta(PRACTICA_ID, true)));
	}

	@SuppressWarnings("java:S107")
	private static Fila fila(Long practicaId, String codigo, Long ofertaId, String total,
			String financiador, String coseguro, LocalDate desde, LocalDate hasta) {

		return new Fila(practicaId, codigo, ofertaId, new BigDecimal(total),
				new BigDecimal(financiador), new BigDecimal(coseguro), desde, hasta);
	}

	private static Convenio convenio() {
		Convenio convenio = new Convenio(ORG_ID, SEDE_ID, 31L, 88L, "OSDE-210", "OSDE 210",
				ModalidadConvenio.POR_PRESTACION, ENERO, DICIEMBRE, "ARS",
				false, false, true, null, null, null);
		ReflectionTestUtils.setField(convenio, "id", CONVENIO_ID);
		return convenio;
	}

	private static ConvenioArancel vigente(
			long id, long practicaId, LocalDate desde, LocalDate hasta) {

		ConvenioArancel arancel = new ConvenioArancel(ORG_ID, SEDE_ID, CONVENIO_ID, practicaId,
				new BigDecimal("12000.00"), new BigDecimal("9600.00"), new BigDecimal("2400.00"),
				"ARS", desde, hasta);
		ReflectionTestUtils.setField(arancel, "id", id);
		return arancel;
	}

	private static CatalogoSnapshot practica(long id, Long organizationId, String codigo) {
		return new CatalogoSnapshot(id, organizationId, codigo, "Practica " + codigo,
				Instant.parse("2020-01-01T00:00:00Z"), null, true, true, 1L, null, 0L);
	}
}
