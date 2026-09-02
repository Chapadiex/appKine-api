package com.akine.contracting;

import com.akine.TestcontainersConfiguration;
import com.akine.contracting.ConvenioFixtures.Desenlace;
import com.akine.contracting.ConvenioFixtures.Escenario;
import com.akine.contracting.application.ArancelCommands.ArancelAltaCommand;
import com.akine.contracting.application.ArancelService;
import com.akine.contracting.application.ArancelView;
import com.akine.contracting.application.ConvenioAltaCommand;
import com.akine.contracting.application.ConvenioService;
import com.akine.contracting.application.ConvenioView;
import com.akine.contracting.application.OperatingActor;
import com.akine.contracting.domain.ModalidadConvenio;
import com.akine.contracting.domain.exception.ArancelSolapadoException;
import com.akine.contracting.domain.exception.ConvenioSolapadoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;

import static com.akine.contracting.ConvenioFixtures.causaEs;
import static com.akine.contracting.ConvenioFixtures.enParalelo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * El criterio de aceptacion de AKINE-03.05: <b>dos convenios del mismo alcance no pueden solaparse,
 * ni siquiera si se crean a la vez</b> (RN-M16-002).
 *
 * <h2>Por que esto no puede ser un test unitario</h2>
 *
 * <p>Un test con mocks verifica el ORDEN de las llamadas —y {@code ConvenioServiceTest} lo hace—
 * pero no que dos transacciones se esperen de verdad. Lo que decide la correctitud aca es el
 * {@code FOR UPDATE} sobre {@code convenio_lock} y el aislamiento de InnoDB, y eso solo lo puede
 * contestar MySQL real.
 *
 * <h2>Lo que ningun indice de la base puede garantizar</h2>
 *
 * <p>El solapamiento. Un unique compara igualdad y dos convenios se pisan cuando sus INTERVALOS se
 * cruzan; uno del 01/01 al 30/06 y otro del 01/03 al 31/12 no comparten un solo valor de columna.
 * MySQL 8.4 no tiene exclusion constraints —son de PostgreSQL— asi que la regla la hace cumplir la
 * validacion de {@code ConvenioService} bajo el lock, y <b>este test es la unica prueba de que
 * funciona</b>.
 *
 * <h2>Las tres condiciones que se ejercitan a la vez</h2>
 *
 * <ol>
 *   <li>{@code READ_COMMITTED}. Con el {@code REPEATABLE READ} por defecto de InnoDB, el segundo
 *       hilo tomaria el lock correctamente y despues leeria una foto anterior en la que el convenio
 *       del primero todavia no existe: los dos entrarian.</li>
 *   <li>La fila-lock creada en una transaccion aparte. El escenario nace <b>sin</b> fila de
 *       {@code convenio_lock}, a proposito: asi este test ejercita el camino de creacion
 *       concurrente, que es donde 05.02 encontro un deadlock que ningun mock podia ver. Si el
 *       iniciador se rompiera, aca aparecerian {@code CannotAcquireLockException} o
 *       {@code UnexpectedRollbackException} en vez del 409 esperado.</li>
 *   <li>El lock tomado antes de leer.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ConvenioConcurrenteIT {

	private static final LocalDate ENERO = LocalDate.of(2027, 1, 1);
	private static final LocalDate MARZO = LocalDate.of(2027, 3, 1);
	private static final LocalDate JUNIO_30 = LocalDate.of(2027, 6, 30);
	private static final LocalDate JULIO = LocalDate.of(2027, 7, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2027, 12, 31);

	@Autowired private ConvenioService convenioService;
	@Autowired private ArancelService arancelService;
	@Autowired private JdbcTemplate jdbc;

	private ConvenioFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new ConvenioFixtures(jdbc);
	}

	@Test
	@DisplayName("dos convenios concurrentes que se pisan: uno entra y el otro recibe 409")
	void dos_convenios_solapados_no_pasan_los_dos() {
		Escenario escenario = fixtures.crear();
		OperatingActor actor = actor(escenario);

		// 01/01-30/06 y 01/03-31/12: SE PISAN y no comparten ningun valor de columna. Es
		// literalmente el caso que ningun UNIQUE puede detectar.
		Callable<ConvenioView> primero = () -> convenioService.crear(
				actor, escenario.consultorioId(), alta(escenario, "A", ENERO, JUNIO_30));
		Callable<ConvenioView> segundo = () -> convenioService.crear(
				actor, escenario.consultorioId(), alta(escenario, "B", MARZO, DICIEMBRE));

		List<Desenlace<ConvenioView>> desenlaces = enParalelo(List.of(primero, segundo));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un convenio entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), ConvenioSolapadoException.class))
				.count())
				.as("y el otro recibe convenio-solapado, NO un deadlock ni un 500 generico. "
						+ "Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		assertThat(contarConvenios(escenario))
				.as("en la base queda UNA sola fila viva para ese alcance")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("dos convenios CONSECUTIVOS concurrentes entran los dos: la regla no es un candado "
			+ "al alcance")
	void dos_convenios_consecutivos_entran_los_dos() {
		// La otra mitad de la regla, y la que hace que valga la pena serializar en vez de prohibir:
		// renovar un convenio para el semestre siguiente es el caso normal, y el lock no puede
		// convertirlo en un rechazo.
		Escenario escenario = fixtures.crear();
		OperatingActor actor = actor(escenario);

		List<Desenlace<ConvenioView>> desenlaces = enParalelo(List.of(
				() -> convenioService.crear(
						actor, escenario.consultorioId(), alta(escenario, "A", ENERO, JUNIO_30)),
				() -> convenioService.crear(
						actor, escenario.consultorioId(), alta(escenario, "B", JULIO, DICIEMBRE))));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("los dos entran. Desenlaces: %s", desenlaces)
				.isEqualTo(2);
		assertThat(contarConvenios(escenario)).isEqualTo(2);
	}

	@Test
	@DisplayName("tres altas concurrentes solapadas: entra una sola, y ninguna muere por deadlock")
	void tres_altas_solapadas() {
		// Tres hilos sobre una sede SIN fila de convenio_lock: es el camino de creacion perezosa
		// que en 05.02 produjo CannotAcquireLockException. Si el iniciador dejara de correr en su
		// propia transaccion, aca aparecerian errores que no son ConvenioSolapadoException.
		Escenario escenario = fixtures.crear();
		OperatingActor actor = actor(escenario);

		List<Desenlace<ConvenioView>> desenlaces = enParalelo(List.of(
				() -> convenioService.crear(
						actor, escenario.consultorioId(), alta(escenario, "A", ENERO, null)),
				() -> convenioService.crear(
						actor, escenario.consultorioId(), alta(escenario, "B", MARZO, null)),
				() -> convenioService.crear(
						actor, escenario.consultorioId(), alta(escenario, "C", JULIO, null))));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("solo uno entra: los tres son abiertos y se pisan entre si. Desenlaces: %s",
						desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), ConvenioSolapadoException.class))
				.count())
				.as("los otros dos reciben el 409 de la etapa y no un error de infraestructura. "
						+ "Desenlaces: %s", desenlaces)
				.isEqualTo(2);
	}

	@Test
	@DisplayName("dos aranceles concurrentes de la MISMA practica que se pisan: entra uno solo")
	void dos_aranceles_solapados_no_pasan_los_dos() {
		Escenario escenario = fixtures.crear();
		OperatingActor actor = actor(escenario);
		long convenioId = convenioService.crear(
				actor, escenario.consultorioId(), alta(escenario, "A", ENERO, DICIEMBRE)).id();

		List<Desenlace<ArancelView>> desenlaces = enParalelo(List.of(
				() -> arancelService.crear(actor, escenario.consultorioId(), convenioId,
						arancel(escenario, "12000.00", ENERO, JUNIO_30)),
				() -> arancelService.crear(actor, escenario.consultorioId(), convenioId,
						arancel(escenario, "18000.00", MARZO, DICIEMBRE))));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un arancel entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), ArancelSolapadoException.class))
				.count())
				.as("y el otro recibe arancel-solapado. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
	}

	@Test
	@DisplayName("dos aranceles concurrentes de PRACTICAS distintas entran los dos")
	void aranceles_de_practicas_distintas_conviven() {
		// El lock es por SEDE, asi que estas dos altas se serializan entre si. Que se serialicen no
		// puede significar que una se rechace: compiten por el lock, no por el periodo.
		Escenario escenario = fixtures.crear();
		long otraPracticaId = fixtures.otraPractica(escenario);
		OperatingActor actor = actor(escenario);
		long convenioId = convenioService.crear(
				actor, escenario.consultorioId(), alta(escenario, "A", ENERO, DICIEMBRE)).id();

		List<Desenlace<ArancelView>> desenlaces = enParalelo(List.of(
				() -> arancelService.crear(actor, escenario.consultorioId(), convenioId,
						arancel(escenario, "12000.00", ENERO, DICIEMBRE)),
				() -> arancelService.crear(actor, escenario.consultorioId(), convenioId,
						new ArancelAltaCommand(otraPracticaId, new BigDecimal("9000.00"),
								new BigDecimal("9000.00"), BigDecimal.ZERO, ENERO, DICIEMBRE))));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("los dos entran: son practicas distintas. Desenlaces: %s", desenlaces)
				.isEqualTo(2);
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static OperatingActor actor(Escenario escenario) {
		return new OperatingActor(
				escenario.accountId(), false, escenario.organizationId(), escenario.consultorioId());
	}

	private static ConvenioAltaCommand alta(
			Escenario escenario, String sufijo, LocalDate desde, LocalDate hasta) {

		return new ConvenioAltaCommand(
				escenario.financiadorId(), escenario.planId(), "CONV-" + sufijo, "Convenio " + sufijo,
				ModalidadConvenio.POR_PRESTACION, desde, hasta, "ARS",
				null, null, null, null, null, null);
	}

	private static ArancelAltaCommand arancel(
			Escenario escenario, String importe, LocalDate desde, LocalDate hasta) {

		return new ArancelAltaCommand(escenario.practicaId(), new BigDecimal(importe),
				new BigDecimal(importe), BigDecimal.ZERO, desde, hasta);
	}

	private long contarConvenios(Escenario escenario) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM convenio
				 WHERE organization_id = ? AND consultorio_id = ? AND active = 1
				""", Long.class, escenario.organizationId(), escenario.consultorioId());
	}
}
