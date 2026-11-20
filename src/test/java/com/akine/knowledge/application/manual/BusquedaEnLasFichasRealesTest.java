package com.akine.knowledge.application.manual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.akine.knowledge.domain.port.ModeloEmbeddings;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Las preguntas que el asistente respondia mal (docs/asistente/evaluacion/manual-resultados.md): la
 * tarea correcta tiene que llegar entre los candidatos que ve el elector, buscando solo por palabras.
 */
class BusquedaEnLasFichasRealesTest {

	private static final IndiceDelManual INDICE = new IndiceDelManual(
			CargadorDelManual.cargar(new PathMatchingResourcePatternResolver()), null);

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"¿dónde cargo una prepaga nueva?|financiadores#dar-de-alta-una-obra-social-o-prepaga-a-mano",
			"¿cómo cierro la atención de la sesión?|atencion#cerrar-la-atencion",
			"¿cómo cambio un turno de día?|agenda#reprogramar-un-turno",
			"¿cómo agrego un paciente nuevo?|padron#dar-de-alta-una-persona"})
	void laTareaCorrectaLlegaEntreLosCandidatos(String pregunta, String esperado) {
		List<String> candidatos = INDICE.buscar(pregunta, f -> true, null, 8).stream()
				.map(p -> p.pasaje().id()).toList();

		assertThat(candidatos).contains(esperado);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"¿dónde cargo una prepaga nueva?|financiadores#dar-de-alta-una-obra-social-o-prepaga-a-mano",
			"¿cómo cierro la atención de la sesión?|atencion#cerrar-la-atencion"})
	@SuppressWarnings("unchecked")
	void unVectorEnContraNoTapaLoQueEncuentranLasPalabras(String pregunta, String esperado) {
		// Vectores adversarios: la tarea correcta es la MENOS parecida a la pregunta, todo lo demas igual.
		ModeloEmbeddings modelo = mock(ModeloEmbeddings.class);
		when(modelo.consulta(anyString())).thenReturn(new float[] {1f, 0f});
		when(modelo.pasajes(anyList())).thenAnswer(inv -> ((List<String>) inv.getArgument(0)).stream()
				.map(texto -> esCorrecta(texto) ? new float[] {0f, 1f} : new float[] {1f, 0f}).toList());
		ObjectProvider<ModeloEmbeddings> proveedor = mock(ObjectProvider.class);
		when(proveedor.getIfAvailable()).thenReturn(modelo);
		IndiceDelManual conVectores = new IndiceDelManual(
				CargadorDelManual.cargar(new PathMatchingResourcePatternResolver()), proveedor);
		conVectores.calcularVectores(modelo);

		List<String> candidatos = conVectores.buscar(pregunta, f -> true, null, 8).stream()
				.map(p -> p.pasaje().id()).toList();

		assertThat(candidatos).contains(esperado);
	}

	private static boolean esCorrecta(String textoDelPasaje) {
		return textoDelPasaje.startsWith("Financiadores — Dar de alta una obra social o prepaga a mano")
				|| textoDelPasaje.startsWith("Atención clínica — Cerrar la atención");
	}
}
