package com.akine.reporting.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.reporting.domain.exception.ConsultorioNoAccesibleException;
import com.akine.reporting.domain.exception.RangoDeReporteInvalidoException;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Las reglas de M23 que cuestan caro si se olvidan.
 *
 * <p>Cinco casos, y ninguno prueba una formula. Las formulas se verifican contra MySQL real y estan
 * anotadas como escenarios diferidos <b>49 a 53</b>: un test unitario que mockee el repositorio y
 * despues compruebe que la suma da lo que el mock devolvio no prueba nada, es el mismo error que
 * este repositorio ya documento —despachar el mismo evento al que el template escucha no prueba la
 * interaccion—.
 *
 * <p>Lo que si se prueba es lo que decide si el reporte es seguro y honesto, que es exactamente lo
 * que el diseno se comprometio a sostener:
 *
 * <ol>
 *   <li><b>Sin {@code reporte:read} no se calcula nada.</b> Si el gate cae despues de los
 *       contribuyentes, un actor sin permiso igual dispara las consultas y los tiempos de respuesta
 *       le dicen cuanta actividad tiene la sede.</li>
 *   <li><b>Una seccion sin permiso se omite y se declara</b>, y las otras se calculan igual. Si
 *       esto se convirtiera en un 403, el recepcionista no podria abrir ningun tablero; si se
 *       omitiera en silencio, leeria "cero" donde dice "no podes ver esto".</li>
 *   <li><b>Sede de otro tenant es 404 y el reporte no se calcula.</b> Es la peor falla posible de
 *       un producto multi-tenant, y una agregacion que se olvida del filtro no falla: devuelve un
 *       numero mas grande.</li>
 *   <li><b>Un rango invalido se rechaza sin invocar a nadie.</b> El tope es lo unico que reemplaza
 *       al export asincrono que no se construyo.</li>
 *   <li><b>Un reporte con seccion clinica se audita.</b> AKINE-04.01: toda lectura clinica se
 *       audita, y el hecho de que el aporte sea agregado no cambia nada.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Reportes y tableros del MVP (M23)")
class ReporteServiceTest {

	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long CUENTA_ID = 1204L;

	private static final ConsultorioSnapshot SEDE = new ConsultorioSnapshot(
			SEDE_ID, ORG_ID, "Sede Centro", "America/Argentina/Cordoba", true);

	private static final OperatingActor ACTOR =
			new OperatingActor(CUENTA_ID, false, ORG_ID, SEDE_ID);

	private static final LocalDate DESDE = LocalDate.of(2026, 9, 1);
	private static final LocalDate HASTA = LocalDate.of(2026, 9, 30);

	@Mock
	private ConsultorioDirectory consultorios;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private PermissionEvaluator permissionEvaluator;

	@Mock
	private AuditTrail auditTrail;

	@Test
	@DisplayName("sin reporte:read no se invoca ningun contribuyente")
	void sinPermisoNoSeCalculaNada() {
		ContribuyenteDePrueba economia = new ContribuyenteDePrueba(
				"economia", "cobro:register", false, ReporteCode.ECONOMICO);
		ReporteService servicio = servicioCon(List.of(economia));

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(SEDE));
		willThrow(new AccessDeniedException("sin reporte:read"))
				.given(permissionGuard).requirePermission(any());

		assertThatThrownBy(() ->
				servicio.generar(ACTOR, SEDE_ID, ReporteCode.ECONOMICO, DESDE, HASTA))
				.isInstanceOf(AccessDeniedException.class);

		// Lo que importa no es el 403: es que las consultas no se hayan disparado. Un gate que
		// corre DESPUES de los contribuyentes filtra por el tiempo de respuesta.
		assertThat(economia.invocaciones).isZero();
	}

	@Test
	@DisplayName("una seccion sin su permiso se omite y se declara; las otras se calculan")
	void seccionSinPermisoSeOmiteYSeDeclara() {
		ContribuyenteDePrueba turnos = new ContribuyenteDePrueba(
				"turnos", "turno:read", false, ReporteCode.OPERATIVO);
		ContribuyenteDePrueba economia = new ContribuyenteDePrueba(
				"economia", "cobro:register", false, ReporteCode.OPERATIVO);
		ReporteService servicio = servicioCon(List.of(turnos, economia));

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(SEDE));
		given(permissionEvaluator.effectivePermissions(CUENTA_ID, ORG_ID, SEDE_ID))
				.willReturn(Set.of("reporte:read", "turno:read"));

		ReporteView vista =
				servicio.generar(ACTOR, SEDE_ID, ReporteCode.OPERATIVO, DESDE, HASTA);

		assertThat(vista.secciones()).extracting(AporteDeReporte::seccion)
				.containsExactly("turnos");
		assertThat(vista.omitidas())
				.extracting(ReporteView.SeccionOmitida::seccion,
						ReporteView.SeccionOmitida::permisoRequerido)
				.containsExactly(org.assertj.core.api.Assertions.tuple("economia", "cobro:register"));

		// La omitida no se calculo: "segun permisos" significa no pedir el dato, no pedirlo y
		// despues taparlo.
		assertThat(economia.invocaciones).isZero();
		assertThat(turnos.invocaciones).isOne();
	}

	@Test
	@DisplayName("una sede de otro tenant es 404 y el reporte no se calcula")
	void sedeDeOtroTenantNoSeReporta() {
		ContribuyenteDePrueba economia = new ContribuyenteDePrueba(
				"economia", null, false, ReporteCode.ECONOMICO);
		ReporteService servicio = servicioCon(List.of(economia));

		given(consultorios.find(anyLong(), anyLong())).willReturn(Optional.empty());

		assertThatThrownBy(() ->
				servicio.generar(ACTOR, SEDE_ID, ReporteCode.ECONOMICO, DESDE, HASTA))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		assertThat(economia.invocaciones).isZero();
		// Ni siquiera se evaluo el permiso: la sede se resuelve primero, y la respuesta es 404 y
		// no 403 para no confirmar que existe.
		verify(permissionGuard, never()).requirePermission(any());
	}

	@Test
	@DisplayName("un periodo invertido o demasiado ancho se rechaza sin invocar contribuyentes")
	void rangoInvalidoSeRechaza() {
		ContribuyenteDePrueba economia = new ContribuyenteDePrueba(
				"economia", null, false, ReporteCode.ECONOMICO);
		ReporteService servicio = servicioCon(List.of(economia));

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(SEDE));

		assertThatThrownBy(() ->
				servicio.generar(ACTOR, SEDE_ID, ReporteCode.ECONOMICO, HASTA, DESDE))
				.isInstanceOf(RangoDeReporteInvalidoException.class);

		assertThatThrownBy(() -> servicio.generar(
				ACTOR, SEDE_ID, ReporteCode.ECONOMICO, DESDE, DESDE.plusYears(3)))
				.isInstanceOf(RangoDeReporteInvalidoException.class);

		assertThat(economia.invocaciones).isZero();
	}

	@Test
	@DisplayName("un reporte con seccion clinica queda auditado; uno sin ella no")
	void elAccesoClinicoSeAudita() {
		ContribuyenteDePrueba sesiones = new ContribuyenteDePrueba(
				"sesiones", null, true, ReporteCode.CLINICO);
		ContribuyenteDePrueba economia = new ContribuyenteDePrueba(
				"economia", null, false, ReporteCode.ECONOMICO);
		ReporteService servicio = servicioCon(List.of(sesiones, economia));

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(SEDE));
		given(permissionEvaluator.effectivePermissions(CUENTA_ID, ORG_ID, SEDE_ID))
				.willReturn(Set.of("reporte:read"));

		servicio.generar(ACTOR, SEDE_ID, ReporteCode.ECONOMICO, DESDE, HASTA);
		verify(auditTrail, never()).record(any());

		servicio.generar(ACTOR, SEDE_ID, ReporteCode.CLINICO, DESDE, HASTA);
		verify(auditTrail).record(any(AuditEntry.class));
	}

	private ReporteService servicioCon(List<ReporteContributor> contribuyentes) {
		return new ReporteService(
				consultorios, permissionGuard, permissionEvaluator, auditTrail, contribuyentes);
	}

	/**
	 * Un contribuyente que solo cuenta cuantas veces lo llamaron.
	 *
	 * <p>Se escribe a mano en vez de mockearse porque lo que se quiere observar es <b>si se lo
	 * invoco</b>, y un mock que devuelve lo que se le dijo que devuelva no distingue "no se
	 * invoco" de "se invoco y dio vacio". Esas dos cosas son justamente las que estos tests
	 * separan.
	 */
	private static final class ContribuyenteDePrueba implements ReporteContributor {

		private final String seccion;
		private final String permiso;
		private final boolean clinica;
		private final Set<ReporteCode> reportes;
		private final List<ConsultaDeReporte> consultas = new ArrayList<>();

		private int invocaciones;

		private ContribuyenteDePrueba(
				String seccion, String permiso, boolean clinica, ReporteCode... reportes) {

			this.seccion = seccion;
			this.permiso = permiso;
			this.clinica = clinica;
			this.reportes = Set.of(reportes);
		}

		@Override
		public Set<ReporteCode> reportes() {
			return reportes;
		}

		@Override
		public String seccion() {
			return seccion;
		}

		@Override
		public String titulo() {
			return seccion;
		}

		@Override
		public String permisoRequerido() {
			return permiso;
		}

		@Override
		public boolean esClinica() {
			return clinica;
		}

		@Override
		public AporteDeReporte aportar(ConsultaDeReporte consulta) {
			invocaciones++;
			consultas.add(consulta);
			return AporteDeReporte.de(seccion, seccion, List.of());
		}
	}
}
