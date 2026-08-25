package com.akine.diferidos;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akine.organization.application.MembershipService;
import com.akine.organization.application.OperatingActor;
import com.akine.organization.domain.exception.LastAdminException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.MembershipNotActiveException;
import com.akine.organization.domain.exception.SelfRevokeNotAllowedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los invariantes de AKINE-01.03 con <b>hilos reales contra MySQL real</b>.
 *
 * <h2>Por que este archivo existe y por que no alcanzan los tests con mocks</h2>
 *
 * <p>Los cuatro bugs de concurrencia que aparecieron en AKINE-01.01 y 01.02 tienen algo en
 * comun: <b>ningun test de un solo hilo los habria encontrado</b>, y todos los tests con mocks
 * daban verde con el diseño roto. El invariante del ultimo administrador es exactamente de esa
 * familia:
 *
 * <p>Dos {@code ORG_ADMIN}, A y B. En el mismo instante A revoca a B y B revoca a A. Cada
 * transaccion cuenta "queda otro admin ademas del que estoy revocando", las dos ven al otro,
 * las dos pasan, y la organizacion queda <b>sin ningun administrador</b>. Con la invitacion
 * fuera de alcance (D-1) no queda ninguna via de rescate dentro del producto: se repara con SQL
 * manual o con un {@code PLATFORM_ADMIN} amparado por acceso de soporte.
 *
 * <p>Un {@code SELECT ... FOR UPDATE} correcto <b>no alcanza</b> para cerrarlo. En
 * {@code REPEATABLE READ} —el default de MySQL— una lectura consistente previa fija el snapshot
 * de la transaccion y el conteo posterior ve datos anteriores al commit del competidor aunque el
 * lock ya se haya adquirido: el lock serializa el ACCESO, no la VISIBILIDAD. Hacen falta las
 * tres cosas a la vez, y este archivo prueba que las tres estan:
 *
 * <ol>
 *   <li>un unico punto de bloqueo de grano grueso —la fila de {@code subscription}— tomado como
 *       primera sentencia;</li>
 *   <li>el conteo que decide hecho con una <b>lectura con lock</b> ({@code FOR SHARE}), que
 *       siempre lee la ultima version confirmada;</li>
 *   <li>{@code READ_COMMITTED} declarado, para que una lectura agregada mas adelante no
 *       reintroduzca el problema en silencio.</li>
 * </ol>
 *
 * <h2>Como se afirma</h2>
 *
 * <p>Contra la BASE, contando filas al final, nunca contra el codigo de respuesta. Un invariante
 * de conteo que se cumple da la misma respuesta hayan corrido los hilos en paralelo o no; lo que
 * un test asi no puede hacer es dar verde con el invariante roto.
 *
 * <p>Y en todos: <b>ninguna transaccion puede morir por infraestructura</b>. Los bugs anteriores
 * se manifestaron como un 500 sobre un contrato que prometia otra cosa —deadlock de InnoDB,
 * {@code AssertionFailure} de una sesion JPA reusada—, asi que se busca esa señal explicitamente.
 *
 * <h2>Por que las memberships se siembran por SQL</h2>
 *
 * <p>La decision D-1 dejo el flujo de invitacion fuera de la etapa, y el alta directa que la
 * reemplaza necesita una cuenta que ya exista. Las cuentas se crean por el camino real
 * ({@code /auth/register} + {@code /auth/login}); lo unico que se siembra son las memberships
 * adicionales, y eso es lo que el plan de tests de la etapa previo explicitamente.
 */
class MembershipConcurrenteIT extends BaseEscenarioDiferido {

	private static final String MOTIVO = "prueba sintetica de concurrencia";

	@Autowired
	private MembershipService membershipService;

	// =================================================================================
	// C-1 — dos revocaciones simetricas de los dos ultimos administradores
	// =================================================================================

	/**
	 * Se repite varias veces porque una carrera no se puede forzar de forma absoluta desde el
	 * proceso de test: lo que se puede es hacerla muy probable y repetirla. Con el
	 * {@code FOR SHARE} reemplazado por un {@code COUNT(*)} comun, este test falla.
	 */
	@RepeatedTest(value = 3, name = "C-1 intento {currentRepetition} de {totalRepetitions}")
	@DisplayName("C-1. Dos revocaciones simetricas: queda EXACTAMENTE un administrador")
	void dos_revocaciones_simetricas_dejan_un_admin() {
		// EXACTAMENTE dos administradores, que es el escenario del diseño. El fundador se baja a
		// PROFESIONAL para que no sea un tercer admin: con tres, las dos revocaciones son
		// legitimas y el invariante ni siquiera se dispara — el test pasaria sin probar nada.
		Escenario escenario = escenarioConAdmins(2);
		degradarAlFundador(escenario);
		long adminA = escenario.membershipsAdmin().get(0);
		long adminB = escenario.membershipsAdmin().get(1);

		List<Concurrencia.Resultado<String>> resultados = Concurrencia.enParalelo(List.of(
				revocar(escenario, escenario.cuentasAdmin().get(0), adminB),
				revocar(escenario, escenario.cuentasAdmin().get(1), adminA)));

		// Un rojo aca tiene que decir POR QUE, y en un test de concurrencia eso es el estado de
		// la base mas los desenlaces de los dos hilos. Sin esto, el flake de relojes que este
		// archivo tuvo se veia como "expected 1 but was 0" y nada mas.
		String diag = diagnostico(escenario, resultados);

		assertSinFallosDeInfraestructura(resultados);

		// El invariante, contra la base: nunca cero.
		assertThat(adminsVigentes(escenario.organizationId()))
				.as("la organizacion no puede quedarse sin administradores%s", diag)
				.isEqualTo(1);

		// Y exactamente una de las dos fallo, con una excepcion de NEGOCIO y no con un 500.
		long rechazadas = resultados.stream().filter(Concurrencia.Resultado::fallo).count();
		assertThat(rechazadas)
				.as("una de las dos revocaciones tiene que ser rechazada%s", diag)
				.isEqualTo(1);
		// Dos desenlaces legitimos, segun quien commitee primero: o la segunda ve que quedaria
		// cero administradores (LastAdmin), o la segunda descubre que su propia membership ya fue
		// revocada y por lo tanto ya no esta en el alcance (404). Las dos son correctas; lo que
		// NO puede pasar es que las dos pasen.
		assertThat(causaDe(resultados)).isInstanceOfAny(
				LastAdminException.class, OrganizationNotFoundException.class);
	}

	// =================================================================================
	// C-6 — N revocaciones simultaneas
	// =================================================================================

	/**
	 * <h2>Por que la forma anterior de este test no podia fallar, y por que igual fallaba</h2>
	 *
	 * <p>El test decia "con tres administradores" y montaba <b>cuatro</b>: los tres sembrados mas
	 * el fundador, que {@code escenarioConAdmins} deja como {@code ORG_ADMIN}. Los diez hilos
	 * revocaban solo a los tres sembrados y usaban al fundador de actor, asi que al cuarto no lo
	 * tocaba nadie. Consecuencias, las dos malas:
	 *
	 * <ul>
	 *   <li><b>La cota inferior era inalcanzable.</b> La organizacion no podia quedarse sin
	 *       administradores <b>aunque se borrara el invariante entero</b>. Medido: con la llamada
	 *       a {@code exigirQueNoSeaElUltimoAdmin} comentada en {@code MembershipService.revoke},
	 *       en las 10 repeticiones quedo siempre <b>una fila {@code ORG_ADMIN ACTIVA}</b> en la
	 *       base — la del fundador, que nadie revocaba. Un {@code isBetween(1, 3)} cuyo limite
	 *       inferior no se puede alcanzar no afirma nada.</li>
	 *   <li><b>Y esa fila de mas era lo unico que si podia hacerlo fallar.</b> El
	 *       {@code valid_from} del fundador lo escribe {@code OnboardingService} con
	 *       {@code Instant.now()} de la <b>JVM</b>, mientras que {@link #adminsVigentes} filtra la
	 *       vigencia contra {@code UTC_TIMESTAMP(6)} del <b>motor</b>. Cuando el reloj del
	 *       contenedor se atrasa respecto del de la JVM —el desfasaje que
	 *       {@link #sembrarMembership} documenta— la vigencia del fundador queda "en el futuro",
	 *       el conteo lo excluye y devuelve cero con el invariante intacto. Medido en 25
	 *       repeticiones: el margen entre el {@code valid_from} del fundador y el reloj del motor
	 *       cayo de +3,88 s a <b>-0,45 s</b> a lo largo de la corrida, y las dos ultimas
	 *       repeticiones dieron rojo con una unica fila {@code ORG_ADMIN ACTIVA} en la base.
	 *       Ese era el flake.</li>
	 * </ul>
	 *
	 * <h2>La forma que si prueba el invariante</h2>
	 *
	 * <p>El fundador baja a {@code CONSULTORIO_ADMIN}. Es el otro rol de la matriz con
	 * {@code colaborador:manage}, asi que <b>sigue pudiendo revocar</b>, pero deja de ser
	 * {@code ORG_ADMIN} y por lo tanto deja de contar para el invariante, que es sobre
	 * {@code ORG_ADMIN} y solo sobre el. Ahora los administradores son exactamente los tres que
	 * dice el nombre, los diez hilos pueden entre todos dejar la organizacion en cero, y lo unico
	 * que lo impide es {@code exigirQueNoSeaElUltimoAdmin}. Comprobado: con esa llamada comentada
	 * el test da rojo con {@code 0} administradores en la base.
	 *
	 * <p>De paso desaparece el flake, sin tocar la asercion: las tres filas que se cuentan las
	 * sembro {@link #sembrarMembership} con el reloj del MOTOR y un minuto de colchon, asi que el
	 * conteo compara ese reloj contra si mismo. La fila del fundador, que es la unica escrita con
	 * el reloj de la JVM, ya no entra en el conteo.
	 *
	 * <p>Los objetivos se repiten a proposito —tres memberships, diez intentos—: el segundo
	 * intento sobre la misma membership tiene que dar un 409 de estado, no un 500 ni una segunda
	 * revocacion.
	 */
	@Test
	@DisplayName("C-6. Diez revocaciones simultaneas con tres administradores: nunca queda cero")
	void diez_revocaciones_simultaneas_nunca_dejan_cero() {
		Escenario escenario = escenarioConAdmins(3);
		degradarAlFundador(escenario, "CONSULTORIO_ADMIN");

		List<Callable<String>> intentos = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			long objetivo = escenario.membershipsAdmin().get(i % 3);
			intentos.add(revocar(escenario, escenario.cuentaFundador(), objetivo));
		}

		List<Concurrencia.Resultado<String>> resultados = Concurrencia.enParalelo(intentos);
		String diag = diagnostico(escenario, resultados);
		assertSinFallosDeInfraestructura(resultados);

		// Nunca cero, y nunca mas de los tres que habia. El limite inferior es el invariante; el
		// superior descarta que una revocacion se haya "deshecho" o que el conteo mire otro tenant.
		assertThat(adminsVigentes(escenario.organizationId()))
				.as("nunca cero, y nunca mas de los que habia%s", diag)
				.isBetween(1L, 3L);

		// Y el invariante tiene que haberse DISPARADO, no solo haberse cumplido: con diez intentos
		// sobre tres administradores, alguno pidio revocar al ultimo que quedaba. Sin esto, una
		// corrida en la que los diez hilos se pisaran de otra forma daria verde sin haber ejercido
		// la regla que el test existe para verificar.
		assertThat(resultados.stream().filter(Concurrencia.Resultado::fallo)
						.map(r -> raiz(r.error()))
						.anyMatch(LastAdminException.class::isInstance))
				.as("el invariante del ultimo administrador tiene que haberse ejercido%s", diag)
				.isTrue();

		// Las que fallaron lo hicieron por reglas de negocio, no por la base.
		resultados.stream().filter(Concurrencia.Resultado::fallo).forEach(r ->
				assertThat(raiz(r.error()))
						.as("desenlace inesperado: %s%s", cadena(r.error()), diag)
						.isInstanceOfAny(LastAdminException.class,
								MembershipNotActiveException.class,
								SelfRevokeNotAllowedException.class));
	}

	// =================================================================================
	// C-3 — dos grants del mismo permiso
	// =================================================================================

	@Test
	@DisplayName("C-3. Dos grants del mismo permiso a la vez: una fila activa y un 409, no un 500")
	void dos_grants_simultaneos_dejan_una_fila() {
		Escenario escenario = escenarioConAdmins(1);
		long objetivo = escenario.membershipsAdmin().get(0);

		Callable<String> otorgar = () -> {
			membershipService.assignGrant(actorFundador(escenario), escenario.organizationId(),
					objetivo, "auditoria:read-clinica", MOTIVO, null);
			return "ok";
		};

		List<Concurrencia.Resultado<String>> resultados =
				Concurrencia.enParalelo(List.of(otorgar, otorgar));
		assertSinFallosDeInfraestructura(resultados);

		// El unique con columna generada decide, no un chequeo previo.
		Long activos = jdbc.queryForObject(
				"SELECT COUNT(*) FROM membership_grant WHERE membership_id = ? AND active = 1",
				Long.class, objetivo);
		assertThat(activos).as("un solo grant activo por permiso y por membership").isEqualTo(1L);

		// Y el perdedor recibe un conflicto de negocio: el camino de clave duplicada no vuelve a
		// tocar la sesion JPA, que es lo que convertiria el 409 en un 500 (AssertionFailure).
		assertThat(resultados.stream().filter(Concurrencia.Resultado::fallo).count()).isEqualTo(1);
		assertThat(causaDe(resultados))
				.isInstanceOf(com.akine.organization.domain.exception.GrantAlreadyActiveException.class);
	}

	// =================================================================================
	// C-4 — cambio de rol y revocacion simultaneos sobre la misma membership
	// =================================================================================

	@RepeatedTest(value = 2, name = "C-4 intento {currentRepetition} de {totalRepetitions}")
	@DisplayName("C-4. Cambio de rol y revocacion a la vez: un solo desenlace, sin 500")
	void cambio_de_rol_y_revocacion_simultaneos() {
		Escenario escenario = escenarioConAdmins(2);
		long objetivo = escenario.membershipsAdmin().get(0);

		long eventosAntes = eventosDe(objetivo);

		List<Concurrencia.Resultado<String>> resultados = Concurrencia.enParalelo(List.of(
				revocar(escenario, escenario.cuentaFundador(), objetivo),
				() -> {
					membershipService.changeRole(actorFundador(escenario),
							escenario.organizationId(), objetivo, "PROFESIONAL", false, null, MOTIVO);
					return "rol";
				}));

		assertSinFallosDeInfraestructura(resultados);

		// Las dos transacciones estan serializadas por el bloqueo del tenant, asi que la segunda
		// entra con el estado YA cambiado y decide contra el. Los desenlaces posibles son dos, y
		// ninguno es "las dos se aplicaron".
		String estado = jdbc.queryForObject(
				"SELECT estado FROM membership WHERE id = ?", String.class, objetivo);
		String rol = jdbc.queryForObject(
				"SELECT role_code FROM membership WHERE id = ?", String.class, objetivo);

		if ("REVOCADA".equals(estado)) {
			// La revocacion gano: el cambio de rol pudo llegar antes (y entonces el rol es
			// PROFESIONAL) o despues (y entonces fue rechazado con 409 membership-not-active).
			assertThat(rol).isIn("ORG_ADMIN", "PROFESIONAL");
		} else {
			assertThat(estado).isEqualTo("ACTIVA");
			assertThat(rol).isEqualTo("PROFESIONAL");
		}

		// La auditoria registra las transiciones REALES: una por operacion que efectivamente
		// ocurrio, ni una de mas.
		long exitos = resultados.stream().filter(r -> !r.fallo()).count();
		assertThat(eventosDe(objetivo) - eventosAntes)
				.as("un evento por operacion confirmada, y ninguno por las rechazadas")
				.isEqualTo(exitos);
	}

	// =================================================================================
	// El orden de bloqueo (C-5 parcial: lo que 01.03 puede afirmar hoy)
	// =================================================================================

	@Test
	@DisplayName("Toda mutacion de membership bloquea la fila de subscription, no la de organization")
	void la_mutacion_bloquea_la_suscripcion() throws Exception {
		// Es la mitad de C-5 que se puede escribir en esta etapa: el alta de sede que cierra el
		// escenario completo no existe hasta AKINE-02.01. Lo que si se puede afirmar es que la
		// transaccion toma la fila de `subscription`, que es el orden de bloqueo unico del
		// sistema y lo que evita el deadlock contra esa etapa.
		//
		// Se comprueba por contencion observable: mientras una transaccion externa mantiene la
		// suscripcion bloqueada, la mutacion de membership NO puede avanzar.
		Escenario escenario = escenarioConAdmins(1);
		long objetivo = escenario.membershipsAdmin().get(0);

		// El lock se toma ANTES de arrancar el hilo, asi que no hay carrera que ganar: el
		// desenlace es determinista.
		try (var conexion = dataSource.getConnection()) {
			conexion.setAutoCommit(false);
			try (var st = conexion.prepareStatement(
					"SELECT id FROM subscription WHERE organization_id = ? FOR UPDATE")) {
				st.setLong(1, escenario.organizationId());
				assertThat(st.executeQuery().next()).isTrue();
			}

			java.util.concurrent.CompletableFuture<String> mutacion =
					java.util.concurrent.CompletableFuture.supplyAsync(() -> {
						membershipService.revoke(actorFundador(escenario),
								escenario.organizationId(), objetivo, MOTIVO);
						return "revocada";
					});

			assertThatThrownBy(() -> mutacion.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS))
					.as("mientras la suscripcion esta bloqueada, la mutacion de membership NO puede "
							+ "avanzar. Si avanza, no esta tomando esa fila y el orden de bloqueo "
							+ "del sistema se rompio: se reintroduce el deadlock contra el alta de "
							+ "sede de AKINE-02.01")
					.isInstanceOf(java.util.concurrent.TimeoutException.class);

			// Al soltar el lock, la mutacion avanza y termina bien.
			conexion.rollback();
			assertThat(mutacion.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("revocada");
			conexion.setAutoCommit(true);
		}
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	/** Un tenant con su fundador y N administradores adicionales sembrados. */
	private record Escenario(
			long organizationId,
			long consultorioId,
			long cuentaFundador,
			List<Long> cuentasAdmin,
			List<Long> membershipsAdmin) {
	}

	private OperatingActor actorFundador(Escenario escenario) {
		return new OperatingActor(escenario.cuentaFundador(), false, escenario.consultorioId());
	}

	private Callable<String> revocar(Escenario escenario, long actorAccountId, long membershipId) {
		return () -> {
			membershipService.revoke(
					new OperatingActor(actorAccountId, false, escenario.consultorioId()),
					escenario.organizationId(), membershipId, MOTIVO);
			return "revocada";
		};
	}

	/**
	 * Crea un tenant y le siembra {@code cantidad} administradores adicionales.
	 *
	 * <p>Las cuentas se dan de alta por el camino real; lo unico que se siembra es la membership,
	 * porque el alta directa de esta etapa exige una cuenta existente y el flujo de invitacion
	 * quedo fuera por D-1.
	 */
	private Escenario escenarioConAdmins(int cantidad) {
		Sesion fundador = altaCompleta("concurrencia");
		List<Long> cuentas = new ArrayList<>();
		List<Long> memberships = new ArrayList<>();

		for (int i = 0; i < cantidad; i++) {
			Sesion companiero = altaCompleta("companiero-" + i);
			cuentas.add(companiero.cuentaId());
			memberships.add(sembrarMembership(
					fundador.organizationId(), companiero.cuentaId(), "ORG_ADMIN"));
		}

		return new Escenario(fundador.organizationId(), fundador.consultorioId(),
				fundador.cuentaId(), cuentas, memberships);
	}

	/**
	 * Inserta la membership sembrada, con {@code valid_from} <b>un minuto en el pasado</b>.
	 *
	 * <h2>Por que no {@code UTC_TIMESTAMP(6)} exacto: el reloj del contenedor se mueve</h2>
	 *
	 * <p>Es la misma trampa que {@code sembrarRolDePlataforma} ya documenta, y aca produjo dos
	 * rojos intermitentes distintos en 25 repeticiones —los dos falsos, con el invariante
	 * intacto—. El reloj del contenedor de MySQL y el de la JVM no solo estan desfasados: el
	 * desfasaje <b>cambia de signo durante la propia corrida</b>. Medido en el fallo: a las
	 * 18:45:03 MySQL iba 0,73 s ADELANTE de la JVM y a las 18:45:32 iba 0,99 s ATRAS. En medio,
	 * el reloj del motor retrocedio: la segunda membership sembrada quedo con un
	 * {@code valid_from} UN SEGUNDO ANTERIOR al de la primera.
	 *
	 * <p>Con {@code valid_from = UTC_TIMESTAMP(6)} exacto eso rompe por los dos lados:
	 *
	 * <ul>
	 *   <li><b>MySQL adelante.</b> La vigencia queda en el futuro respecto del
	 *       {@code Instant.now()} de la JVM con el que el evaluador de permisos la compara, los
	 *       dos actores dejan de ser miembros vigentes y las dos revocaciones mueren con un 404.
	 *       No se revoca nada y el test acusa al invariante de dejar DOS administradores.</li>
	 *   <li><b>MySQL atras.</b> La fila sembrada tiene un {@code valid_from} posterior al
	 *       {@code UTC_TIMESTAMP(6)} que la asercion usa despues, asi que el administrador que
	 *       SI sobrevivio no se cuenta: {@code adminsVigentes} devuelve 0 con el invariante
	 *       cumplido, que es exactamente el rojo que se investigo.</li>
	 * </ul>
	 *
	 * <p>Un minuto de margen es mucho mas que cualquier desfasaje observado y no toca nada de lo
	 * que este archivo afirma: el invariante es un conteo de administradores, no un limite de
	 * vigencia. La vigencia tiene sus propios tests, que la fijan a proposito.
	 */
	private long sembrarMembership(long organizationId, long accountId, String rol) {
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, estado, valid_from, active, version,
				                        created_at, updated_at)
				VALUES (?, NULL, ?, ?, 0, 'ACTIVA',
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, accountId, rol);
		Long id = jdbc.queryForObject(
				"SELECT id FROM membership WHERE organization_id = ? AND account_id = ?",
				Long.class, organizationId, accountId);
		assertThat(id).isNotNull();
		return id;
	}

	/**
	 * Administradores vigentes del tenant, contados contra la base.
	 *
	 * <p>El instante se toma con {@code UTC_TIMESTAMP(6)} del propio motor y no se pasa como
	 * parametro: {@code JdbcTemplate} bindea un {@code Instant} usando la zona de la conexion, y
	 * en una maquina en UTC-3 eso corre el "ahora" tres horas hacia atras. Una membership recien
	 * creada queda con {@code valid_from} en el futuro respecto de ese parametro y el conteo da
	 * cero: el test fallaria acusando al invariante de un bug que es de la asercion.
	 *
	 * <p><b>Usar el reloj del motor evita el problema de la ZONA, no el del DESFASAJE.</b> El
	 * reloj del contenedor se mueve —y retrocede— durante la corrida, asi que este conteo tampoco
	 * seria confiable si las filas sembradas tuvieran {@code valid_from} pegado a
	 * {@code UTC_TIMESTAMP(6)}: lo que lo hace confiable es que {@link #sembrarMembership} las
	 * siembra un minuto en el pasado. El razonamiento completo, con las mediciones, esta ahi.
	 */
	private long adminsVigentes(long organizationId) {
		Long total = jdbc.queryForObject("""
				SELECT COUNT(*) FROM membership
				 WHERE organization_id = ? AND role_code = 'ORG_ADMIN'
				   AND estado = 'ACTIVA' AND active = 1
				   AND valid_from <= UTC_TIMESTAMP(6)
				   AND (valid_until IS NULL OR valid_until > UTC_TIMESTAMP(6))
				""", Long.class, organizationId);
		return total == null ? 0L : total;
	}

	private long eventosDe(long membershipId) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE entity_type = 'Membership' AND entity_id = ?",
				Long.class, membershipId);
		return total == null ? 0L : total;
	}

	/**
	 * Exige que ningun hilo haya muerto por infraestructura.
	 *
	 * <p>Los cuatro bugs de concurrencia anteriores se manifestaron como un 500 sobre un contrato
	 * que prometia otra cosa: deadlock de InnoDB, o una sesion JPA reusada despues de un flush
	 * fallido. Esa es la señal, y hay que buscarla explicitamente en vez de esperar a que rompa
	 * una asercion funcional.
	 */
	private void assertSinFallosDeInfraestructura(List<Concurrencia.Resultado<String>> resultados) {
		resultados.stream().filter(Concurrencia.Resultado::fallo).forEach(r -> {
			// Se recorre la CADENA entera y no solo la causa raiz: un deadlock de InnoDB llega
			// envuelto en CannotAcquireLockException con una SQLException adentro, asi que mirar
			// solo la raiz lo dejaria pasar — que es exactamente como estos bugs se esconden.
			for (Throwable actual = r.error(); actual != null; actual = actual.getCause()) {
				String nombre = actual.getClass().getName();
				assertThat(nombre)
						.as("ningun hilo puede morir por infraestructura. Cadena: %s", cadena(r.error()))
						.doesNotContain("CannotAcquireLock")
						.doesNotContain("DeadlockLoser")
						.doesNotContain("TransactionRollbackException")
						// Una sesion JPA reusada tras un flush fallido devuelve 500.
						.doesNotContain("AssertionFailure");
				if (actual.getCause() == actual) {
					break;
				}
			}
		});
	}

	/** Toda la cadena de causas, para que un fallo diga que paso y no solo que fallo. */
	private String cadena(Throwable error) {
		StringBuilder texto = new StringBuilder();
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			texto.append(actual).append(" <- ");
			if (actual.getCause() == actual) {
				break;
			}
		}
		return texto.toString();
	}

	/** Saca al fundador de {@code ORG_ADMIN}, para dejar solo los admins sembrados. */
	private void degradarAlFundador(Escenario escenario) {
		degradarAlFundador(escenario, "PROFESIONAL");
	}

	/**
	 * Le cambia el rol al fundador.
	 *
	 * <p>El rol destino no es un detalle: {@code PROFESIONAL} lo deja sin
	 * {@code colaborador:manage} y por lo tanto sin poder revocar a nadie, mientras que
	 * {@code CONSULTORIO_ADMIN} se lo conserva pero lo saca del conteo del invariante, que mira
	 * {@code ORG_ADMIN} y nada mas. C-1 quiere lo primero; C-6, lo segundo.
	 */
	private void degradarAlFundador(Escenario escenario, String rol) {
		int filas = jdbc.update(
				"UPDATE membership SET role_code = ? WHERE organization_id = ? AND account_id = ?",
				rol, escenario.organizationId(), escenario.cuentaFundador());
		assertThat(filas).isEqualTo(1);
	}

	private Throwable causaDe(List<Concurrencia.Resultado<String>> resultados) {
		return resultados.stream()
				.filter(Concurrencia.Resultado::fallo)
				.map(r -> raiz(r.error()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("se esperaba que un hilo fallara"));
	}

	private Throwable raiz(Throwable error) {
		Throwable actual = error;
		while (actual.getCause() != null && actual.getCause() != actual) {
			actual = actual.getCause();
		}
		return actual;
	}

	/**
	 * Estado de la base y desenlace de cada hilo, para adjuntar a un rojo.
	 *
	 * <p><b>Incluye los DOS relojes a proposito.</b> El flake que este archivo tuvo era un
	 * desfasaje entre el reloj del contenedor de MySQL y el de la JVM —ver
	 * {@link #sembrarMembership}—, y sin ese par de valores el rojo era un "expected 1 but was 0"
	 * indistinguible de un invariante roto de verdad. La lectura de la JVM se toma antes y
	 * despues de la del motor para que el ida y vuelta de la consulta no se confunda con el
	 * desfasaje.
	 */
	private String diagnostico(
			Escenario escenario, List<Concurrencia.Resultado<String>> resultados) {

		StringBuilder sb = new StringBuilder("\n  [estado final]");
		sb.append("\n    reloj jvm=").append(Instant.now())
				.append(" mysql=").append(jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", String.class))
				.append(" jvm=").append(Instant.now());
		for (Map<String, Object> fila : jdbc.queryForList("""
				SELECT id, account_id, role_code, estado, active, is_founder,
				       valid_from, valid_until, version
				  FROM membership WHERE organization_id = ? ORDER BY id
				""", escenario.organizationId())) {
			sb.append("\n    membership ").append(fila);
		}
		for (Concurrencia.Resultado<String> r : resultados) {
			sb.append("\n    hilo: ").append(r.fallo() ? cadena(r.error()) : "ok=" + r.valor());
		}
		return sb.append('\n').toString();
	}
}
