package com.akine.knowledge.application;

import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.BusquedaAjustes;
import com.akine.knowledge.domain.port.AlmacenVectorial;
import com.akine.knowledge.domain.port.AlmacenVectorial.Coincidencia;
import com.akine.knowledge.domain.port.AlmacenVectorial.FragmentoEncontrado;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import com.akine.knowledge.spi.CaminoBusqueda;
import com.akine.knowledge.spi.PasajeRecuperado;
import com.akine.knowledge.spi.ResultadoBusqueda;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("BusquedaService")
class BusquedaServiceTest {

	private static final OperatingActor ACTOR = new OperatingActor(99L, false, 1L);
	private static final BusquedaAjustes AJUSTES =
			new BusquedaAjustes(new BigDecimal("0.818"), new BigDecimal("0.5"), 2);

	private BibliotecaAcceso acceso;
	private AlmacenVectorial almacen;
	private ModeloEmbeddings modelo;
	private ObjectProvider<ModeloEmbeddings> proveedor;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		acceso = mock(BibliotecaAcceso.class);
		almacen = mock(AlmacenVectorial.class);
		modelo = mock(ModeloEmbeddings.class);
		proveedor = mock(ObjectProvider.class);
		when(modelo.id()).thenReturn("m");
		when(modelo.consulta(anyString())).thenReturn(new float[] {1f});
		when(almacen.fragmentos(anyLong(), any())).thenReturn(List.of(
				fragmento(10), fragmento(11), fragmento(12)));
	}

	private BusquedaService service(boolean habilitada) {
		return new BusquedaService(acceso, new BibliotecaAjustes(habilitada, 1000), AJUSTES, almacen, proveedor);
	}

	private static FragmentoEncontrado fragmento(long id) {
		return new FragmentoEncontrado(id, 7L, "Libro", 1, 2, "texto " + id);
	}

	private static Coincidencia c(long id, String puntaje) {
		return new Coincidencia(id, new BigDecimal(puntaje));
	}

	@Test
	@DisplayName("con modelo busca por vector y usa el corte de 0.818")
	void vector_con_su_corte() {
		when(proveedor.getIfAvailable()).thenReturn(modelo);
		when(almacen.porVector(eq(1L), eq("m"), any(), eq(5)))
				.thenReturn(List.of(c(10, "0.9000"), c(11, "0.8180"), c(12, "0.7000")));

		ResultadoBusqueda r = service(true).buscar(ACTOR, 1L, "  lumbalgia  ", 5);

		verify(acceso).exigir(ACTOR, 1L);
		verify(modelo).consulta("lumbalgia");
		assertThat(r.camino()).isEqualTo(CaminoBusqueda.VECTOR);
		assertThat(r.corte()).isEqualTo(0.818);
		assertThat(r.alcanza()).isTrue();
		assertThat(r.pasajes()).extracting(PasajeRecuperado::superaCorte).containsExactly(true, true, false);
		verify(almacen, never()).porPalabras(anyLong(), anyString(), anyInt());
	}

	@Test
	@DisplayName("sin modelo busca por palabras y usa el corte de 0.5")
	void sin_modelo_palabras() {
		when(proveedor.getIfAvailable()).thenReturn(null);
		when(almacen.porPalabras(1L, "lumbalgia", 5)).thenReturn(List.of(c(10, "0.6000"), c(11, "0.4000")));

		ResultadoBusqueda r = service(true).buscar(ACTOR, 1L, "lumbalgia", 5);

		assertThat(r.camino()).isEqualTo(CaminoBusqueda.PALABRAS);
		assertThat(r.corte()).isEqualTo(0.5);
		assertThat(r.alcanza()).isFalse();
		assertThat(r.pasajes()).extracting(PasajeRecuperado::superaCorte).containsExactly(true, false);
	}

	@Test
	@DisplayName("si el modelo lanza, cae a palabras")
	void modelo_que_lanza_palabras() {
		when(proveedor.getIfAvailable()).thenReturn(modelo);
		when(modelo.consulta(anyString())).thenThrow(new IllegalStateException("onnx caido"));
		when(almacen.porPalabras(1L, "lumbalgia", 5)).thenReturn(List.of());

		ResultadoBusqueda r = service(true).buscar(ACTOR, 1L, "lumbalgia", 5);

		assertThat(r.camino()).isEqualTo(CaminoBusqueda.PALABRAS);
		assertThat(r.pasajes()).isEmpty();
		verify(almacen, never()).porVector(anyLong(), anyString(), any(), anyInt());
	}

	@Test
	@DisplayName("conserva el orden del almacen y omite los ids que ya no son buscables")
	void hidrata_en_orden() {
		when(proveedor.getIfAvailable()).thenReturn(modelo);
		when(almacen.porVector(anyLong(), anyString(), any(), anyInt()))
				.thenReturn(List.of(c(12, "0.9500"), c(99, "0.9000"), c(10, "0.8500")));

		ResultadoBusqueda r = service(true).buscar(ACTOR, 1L, "x y z", 3);

		assertThat(r.pasajes()).extracting(PasajeRecuperado::texto).containsExactly("texto 12", "texto 10");
		assertThat(r.pasajes()).extracting(PasajeRecuperado::puntaje).containsExactly(0.95, 0.85);
		assertThat(r.alcanza()).isTrue();
	}

	@Test
	@DisplayName("consulta en blanco, de mas de 600 caracteres o k fuera de 1..20: IllegalArgumentException")
	void validacion() {
		BusquedaService s = service(true);
		assertThatThrownBy(() -> s.buscar(ACTOR, 1L, "   ", 5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> s.buscar(ACTOR, 1L, null, 5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> s.buscar(ACTOR, 1L, "a".repeat(601), 5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> s.buscar(ACTOR, 1L, "ok", 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> s.buscar(ACTOR, 1L, "ok", 21)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(almacen);
	}

	@Test
	@DisplayName("el directory no exige permiso, y apagado devuelve vacio sin buscar")
	void directory() {
		when(proveedor.getIfAvailable()).thenReturn(null);
		when(almacen.porPalabras(1L, "ok", 5)).thenReturn(List.of());

		service(true).buscar(1L, "ok", 5);
		ResultadoBusqueda apagada = service(false).buscar(1L, "ok", 5);

		verifyNoInteractions(acceso);
		assertThat(apagada.alcanza()).isFalse();
		assertThat(apagada.pasajes()).isEmpty();
		verify(almacen).porPalabras(1L, "ok", 5);
		assertThatThrownBy(() -> service(true).buscar(0L, "ok", 5)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("sin ningun vector del modelo vigente cae a palabras")
	void sin_vectores_del_modelo_cae_a_palabras() {
		when(proveedor.getIfAvailable()).thenReturn(modelo);
		when(almacen.porVector(eq(1L), eq("m"), any(), eq(5))).thenReturn(List.of());
		when(almacen.porPalabras(1L, "lumbalgia", 5)).thenReturn(List.of(c(10, "0.6000"), c(11, "0.5500")));

		ResultadoBusqueda r = service(true).buscar(ACTOR, 1L, "lumbalgia", 5);

		assertThat(r.camino()).isEqualTo(CaminoBusqueda.PALABRAS);
		assertThat(r.corte()).isEqualTo(0.5);
		assertThat(r.pasajes()).hasSize(2);
		assertThat(r.alcanza()).isTrue();
	}
}
