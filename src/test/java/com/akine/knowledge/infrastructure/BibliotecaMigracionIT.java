package com.akine.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akine.TestcontainersConfiguration;
import com.akine.knowledge.domain.EstadoLibro;
import com.akine.knowledge.domain.EstadoVisibleLibro;
import com.akine.knowledge.domain.Libro;
import com.akine.knowledge.domain.LibroFragmento;
import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import com.akine.knowledge.domain.port.LibroFragmentoRepositoryPort;

/** V144: esquema de la biblioteca y sus puertos, contra MySQL real. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class BibliotecaMigracionIT {

	@Autowired JdbcTemplate jdbc;
	@Autowired LibroRepository libros;
	@Autowired LibroFragmentoRepositoryPort fragmentos;
	@Autowired BibliotecaVersionPort version;
	@Autowired PlatformTransactionManager tm;

	long orgId;
	TransactionTemplate tx;

	@BeforeEach
	void organizacion() {
		tx = new TransactionTemplate(tm);
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + sufijo, "bib-it-" + sufijo);
		orgId = jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class,
				"bib-it-" + sufijo);
	}

	private Libro libro(String hash, Long reemplaza) {
		return new Libro(orgId, "Titulo " + hash.substring(0, 4), "Autor", null, "Kinesiologia",
				UUID.randomUUID().toString().replace("-", ""), "a.pdf", "application/pdf", 10L, hash,
				reemplaza, Instant.now(), 1L);
	}

	private static String hash(char c) {
		return String.valueOf(c).repeat(64);
	}

	@Test
	void segundoLibroActivoConElMismoHashChocaPeroInactivoOReemplazoNo() {
		Libro original = libros.saveAndFlush(libro(hash('a'), null));

		assertThatThrownBy(() -> libros.saveAndFlush(libro(hash('a'), null)))
				.isInstanceOf(DataIntegrityViolationException.class);

		Libro reemplazo = libros.saveAndFlush(libro(hash('a'), original.getId()));
		assertThat(reemplazo.getId()).isNotNull();

		Libro inactivo = libro(hash('b'), null);
		inactivo.desactivar();
		libros.saveAndFlush(inactivo);
		libros.saveAndFlush(libro(hash('b'), null));
		assertThat(inactivo.estadoVisible()).isEqualTo(EstadoVisibleLibro.INACTIVO);
	}

	@Test
	void borrarUnLibroCascadeaSusFragmentos() {
		Libro l = libros.saveAndFlush(libro(hash('c'), null));
		LibroFragmento f1 = new LibroFragmento(orgId, l.getId(), 0, 1, 1, "uno", false);
		LibroFragmento f2 = new LibroFragmento(orgId, l.getId(), 1, 1, 2, "dos", true);
		tx.executeWithoutResult(s -> fragmentos.reemplazarDeLibro(l.getId(), List.of(f1, f2)));
		assertThat(fragmentos.contarPorLibro(l.getId())).isEqualTo(2);
		assertThat(fragmentos.sospechososDe(l.getId())).hasSize(1);

		tx.executeWithoutResult(s -> fragmentos.reemplazarDeLibro(l.getId(), List.of(f1)));
		assertThat(fragmentos.contarPorLibro(l.getId())).isEqualTo(1);

		libros.deleteById(l.getId());
		libros.flush();
		assertThat(fragmentos.contarPorLibro(l.getId())).isZero();
	}

	private long fragmentoDe(Libro l, boolean sospechoso) {
		tx.executeWithoutResult(s -> fragmentos.reemplazarDeLibro(l.getId(),
				List.of(new LibroFragmento(orgId, l.getId(), 0, 1, 1, "x", sospechoso))));
		return jdbc.queryForObject("SELECT id FROM libro_fragmento WHERE libro_id = ?", Long.class,
				l.getId());
	}

	@Test
	void vectoresSeReclamanSoloDeLibrosListosYActivosYSeActualizan() {
		Libro listo = libro(hash('d'), null);
		listo.marcarListo(1);
		listo = libros.saveAndFlush(listo);
		Libro inactivo = libro(hash('1'), null);
		inactivo.marcarListo(1);
		inactivo.desactivar();
		inactivo = libros.saveAndFlush(inactivo);
		Libro enError = libro(hash('2'), null);
		enError.marcarError("falla");
		enError = libros.saveAndFlush(enError);
		Libro listoSospechoso = libro(hash('3'), null);
		listoSospechoso.marcarListo(1);
		listoSospechoso = libros.saveAndFlush(listoSospechoso);

		long id = fragmentoDe(listo, false);
		long idInactivo = fragmentoDe(inactivo, false);
		long idError = fragmentoDe(enError, false);
		long idSospechoso = fragmentoDe(listoSospechoso, true);

		List<Long> pendientes = tx.execute(s -> fragmentos.reclamarSinVector("modelo-v1", 1000));
		assertThat(pendientes).contains(id)
				.doesNotContain(idInactivo, idError, idSospechoso);

		byte[] vector = new byte[3072];
		vector[0] = 7;
		tx.executeWithoutResult(s -> fragmentos.actualizarVector(id, vector, "modelo-v1"));
		List<Long> v1 = tx.execute(s -> fragmentos.reclamarSinVector("modelo-v1", 1000));
		assertThat(v1).doesNotContain(id);
		List<Long> v2 = tx.execute(s -> fragmentos.reclamarSinVector("modelo-v2", 1000));
		assertThat(v2).contains(id);
		byte[] leido = jdbc.queryForObject("SELECT vector FROM libro_fragmento WHERE id = ?", byte[].class, id);
		assertThat(leido).hasSize(3072).startsWith((byte) 7);
	}

	@Test
	void reclamarPendienteDesdeDosTransaccionesDevuelveLibrosDistintos() throws Exception {
		libros.saveAndFlush(libro(hash('e'), null));
		libros.saveAndFlush(libro(hash('f'), null));

		CountDownLatch primeroReclamo = new CountDownLatch(1);
		CountDownLatch soltar = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Long> t1 = pool.submit(() -> txRc().execute(s -> {
				Optional<Libro> l = libros.reclamarPendiente(Instant.now());
				primeroReclamo.countDown();
				await(soltar);
				return l.orElseThrow().getId();
			}));
			assertThat(primeroReclamo.await(10, TimeUnit.SECONDS)).isTrue();
			Long segundo = pool.submit(() -> txRc().execute(s ->
					libros.reclamarPendiente(Instant.now()).orElseThrow().getId())).get(10, TimeUnit.SECONDS);
			soltar.countDown();
			Long primero = t1.get(10, TimeUnit.SECONDS);
			assertThat(primero).isNotEqualTo(segundo);
		} finally {
			soltar.countDown();
			pool.shutdownNow();
		}
	}

	private TransactionTemplate txRc() {
		TransactionTemplate t = new TransactionTemplate(tm);
		t.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
		return t;
	}

	private static void await(CountDownLatch l) {
		try {
			l.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void devolverTrabadosYBuscar() {
		Libro l = libro(hash('g'), null);
		l.marcarProcesando(Instant.now().minusSeconds(3600));
		l = libros.saveAndFlush(l);
		Integer devueltos = tx.execute(s -> libros.devolverTrabados(Instant.now().minusSeconds(60)));
		assertThat(devueltos).isGreaterThanOrEqualTo(1);
		Libro releido = libros.findByIdAndOrganization(l.getId(), orgId).orElseThrow();
		assertThat(releido.getEstado()).isEqualTo(EstadoLibro.PENDIENTE);
		assertThat(libros.findByIdAndOrganization(l.getId(), orgId + 999)).isEmpty();

		var pagina = libros.buscar(orgId, "autor", "Kinesiologia", EstadoLibro.PENDIENTE, true,
				org.springframework.data.domain.PageRequest.of(0, 10));
		assertThat(pagina.getTotalElements()).isEqualTo(1);
		assertThat(libros.buscar(orgId, null, null, null, null,
				org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
	}

	@Test
	void laVersionAvanza() {
		assertThat(version.actual(orgId)).isZero();
		tx.executeWithoutResult(s -> version.avanzar(orgId));
		tx.executeWithoutResult(s -> version.avanzar(orgId));
		assertThat(version.actual(orgId)).isEqualTo(2);
	}
}
