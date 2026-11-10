package com.akine.knowledge.application;

import com.akine.knowledge.application.LibroService.ArchivoSubido;
import com.akine.knowledge.application.LibroService.DatosDeLibro;
import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.EstadoLibro;
import com.akine.knowledge.domain.Libro;
import com.akine.knowledge.domain.exception.LibroArchivoNoAceptadoException;
import com.akine.knowledge.domain.exception.LibroArchivoNoAceptadoException.Causa;
import com.akine.knowledge.domain.exception.LibroDuplicadoException;
import com.akine.knowledge.domain.exception.LibroEnProcesoException;
import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import com.akine.knowledge.domain.port.LibroFragmentoRepositoryPort;
import com.akine.knowledge.domain.port.LibroRepositoryPort;
import com.akine.platform.spi.archivo.AlmacenDeArchivos;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LibroService")
class LibroServiceTest {

	private static final OperatingActor ACTOR = new OperatingActor(99L, false, 1L);
	private static final DatosDeLibro DATOS = new DatosDeLibro("Titulo", null, null, null);

	private BibliotecaAcceso acceso;
	private LibroRepositoryPort libros;
	private BibliotecaVersionPort version;
	private AlmacenDeArchivos almacen;
	private LibroService service;

	@BeforeEach
	void setUp() {
		acceso = mock(BibliotecaAcceso.class);
		libros = mock(LibroRepositoryPort.class);
		version = mock(BibliotecaVersionPort.class);
		almacen = mock(AlmacenDeArchivos.class);
		service = new LibroService(acceso, new BibliotecaAjustes(true, 1000), libros,
				mock(LibroFragmentoRepositoryPort.class), version, almacen, mock(AuditTrail.class),
				mock(PlatformTransactionManager.class));
		when(libros.guardarYSincronizar(any())).thenAnswer(inv -> {
			Libro l = inv.getArgument(0);
			ReflectionTestUtils.setField(l, "id", 5L);
			return l;
		});
	}

	private static ArchivoSubido pdf(String cuerpo) {
		return new ArchivoSubido("a.pdf", ("%PDF-1.7 " + cuerpo).getBytes());
	}

	private static Libro libro(long id, EstadoLibro estado) {
		Libro l = new Libro(1L, "T", null, null, null, "k" + id + ".pdf", "a.pdf", "application/pdf",
				10L, "h".repeat(64), null, Instant.now(), 99L);
		ReflectionTestUtils.setField(l, "id", id);
		ReflectionTestUtils.setField(l, "estado", estado);
		return l;
	}

	@Test
	@DisplayName("dos altas del mismo contenido dan un libro y un 409 libro-duplicado")
	void mismo_hash_segunda_alta_es_duplicada() {
		LibroView primero = service.subir(ACTOR, 1L, DATOS, pdf("x"), true);
		assertThat(primero.estado()).isEqualTo(com.akine.knowledge.domain.EstadoVisibleLibro.PENDIENTE);
		when(libros.activoPorHash(any(Long.class), anyString())).thenReturn(Optional.of(libro(5, EstadoLibro.PENDIENTE)));

		assertThatThrownBy(() -> service.subir(ACTOR, 1L, DATOS, pdf("x"), true))
				.isInstanceOf(LibroDuplicadoException.class)
				.extracting(e -> ((LibroDuplicadoException) e).getLibroId()).isEqualTo(5L);
		// el archivo de la segunda se escribio primero y se borra
		verify(almacen, org.mockito.Mockito.times(2)).guardar(anyString(), anyString(), any());
		verify(almacen).borrar(anyString(), anyString());
	}

	@Test
	@DisplayName("vacio, muy grande y no-PDF se rechazan antes de tocar el almacen")
	void archivo_no_aceptado() {
		assertThatThrownBy(() -> service.subir(ACTOR, 1L, DATOS, new ArchivoSubido("a", new byte[0]), true))
				.extracting(e -> ((LibroArchivoNoAceptadoException) e).getCausa()).isEqualTo(Causa.VACIO);
		assertThatThrownBy(() -> service.subir(ACTOR, 1L, DATOS,
				new ArchivoSubido("a", new byte[2000]), true))
				.extracting(e -> ((LibroArchivoNoAceptadoException) e).getCausa()).isEqualTo(Causa.MUY_GRANDE);
		assertThatThrownBy(() -> service.subir(ACTOR, 1L, DATOS,
				new ArchivoSubido("a.pdf", "MZ-exe-renombrado".getBytes()), true))
				.extracting(e -> ((LibroArchivoNoAceptadoException) e).getCausa()).isEqualTo(Causa.NO_ES_PDF);
		verify(almacen, never()).guardar(anyString(), anyString(), any());
	}

	@Test
	@DisplayName("sin declaracion de derechos es 400")
	void sin_declaracion() {
		assertThatThrownBy(() -> service.subir(ACTOR, 1L, DATOS, pdf("x"), false))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("reemplazo y reproceso de un libro en proceso, o con reemplazo pendiente, son 409")
	void en_proceso() {
		when(libros.findByIdAndOrganization(10L, 1L)).thenReturn(Optional.of(libro(10, EstadoLibro.PROCESANDO)));
		assertThatThrownBy(() -> service.reprocesar(ACTOR, 1L, 10L)).isInstanceOf(LibroEnProcesoException.class);
		assertThatThrownBy(() -> service.reemplazar(ACTOR, 1L, 10L, pdf("y"), true))
				.isInstanceOf(LibroEnProcesoException.class);

		Libro listo = libro(11, EstadoLibro.LISTO);
		when(libros.findByIdAndOrganization(11L, 1L)).thenReturn(Optional.of(listo));
		when(libros.reemplazoPendienteDe(11L)).thenReturn(Optional.of(libro(12, EstadoLibro.PENDIENTE)));
		assertThatThrownBy(() -> service.reemplazar(ACTOR, 1L, 11L, pdf("y"), true))
				.isInstanceOf(LibroEnProcesoException.class);
		verify(almacen, never()).guardar(anyString(), anyString(), any());
	}

	@Test
	@DisplayName("eliminar borra el reemplazo pendiente antes que el original y avanza la version")
	void eliminar_con_reemplazo() {
		Libro original = libro(20, EstadoLibro.PROCESANDO);
		Libro reemplazo = libro(21, EstadoLibro.PENDIENTE);
		when(libros.findByIdAndOrganization(20L, 1L)).thenReturn(Optional.of(original));
		when(libros.reemplazoPendienteDe(20L)).thenReturn(Optional.of(reemplazo));

		service.eliminar(ACTOR, 1L, 20L);

		InOrder orden = inOrder(libros, version, almacen);
		orden.verify(libros).delete(reemplazo);
		orden.verify(libros).delete(original);
		orden.verify(version).avanzar(1L);
		orden.verify(almacen).borrar("biblioteca", "k21.pdf");
		orden.verify(almacen).borrar("biblioteca", "k20.pdf");
	}
}
