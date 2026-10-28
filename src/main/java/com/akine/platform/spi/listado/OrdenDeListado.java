package com.akine.platform.spi.listado;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Sort;

/**
 * El criterio de orden que un cliente pide para un listado paginado (DP-45, punto 1).
 *
 * <p><b>Formato del parametro {@code sort}.</b> Un solo valor {@code campo,asc} o
 * {@code campo,desc}, en minusculas. No es repetible: un listado se ordena por UN campo que elige
 * quien mira, y el desempate lo pone siempre el servidor —el identificador, unico—, para que el
 * paginado sea estable aunque muchas filas compartan el valor del campo. Mandar el parametro dos
 * veces llega a Spring como un solo texto con las dos partes unidas por coma y se rechaza igual
 * que un campo desconocido.
 *
 * <p><b>Cada operacion declara su lista blanca</b> —el enum del contrato— y un campo o una
 * direccion fuera de ella es 400 {@code validation-error}: se lanza
 * {@link IllegalArgumentException}, que el advice global ya traduce. Nunca se pasa el texto del
 * cliente a una consulta: cada modulo traduce el campo a SU columna o a SU comparador.
 *
 * <p><b>Sin {@code sort} el orden es exactamente el de antes.</b> Por eso {@link #leer} devuelve
 * {@link Optional#empty()} y no un orden por defecto: el orden historico de cada listado lo sigue
 * decidiendo su consulta, y eso es lo que hace al cambio aditivo.
 */
public record OrdenDeListado(String campo, boolean descendente) {

	/** El nombre del parametro de query, igual en todas las operaciones. */
	public static final String PARAMETRO = "sort";

	/**
	 * Texto comun para la descripcion del parametro en el contrato. Cada operacion le agrega
	 * cual es su orden por defecto.
	 */
	public static final String DESCRIPCION = "Orden de la pagina: un solo valor campo,asc o "
			+ "campo,desc, entre los que declara el enum. No es repetible. El servidor desempata "
			+ "siempre por id, para que el paginado sea estable. Un valor fuera del enum responde "
			+ "400 validation-error. Sin el parametro, el orden de siempre: ";

	public OrdenDeListado {
		if (campo == null || campo.isBlank()) {
			throw new IllegalArgumentException("El campo de orden no puede estar vacio");
		}
	}

	/**
	 * Lee el parametro {@code sort} contra la lista blanca de la operacion.
	 *
	 * @param sort el valor recibido, o {@code null}
	 * @param permitidos los campos que la operacion acepta
	 * @return vacio si no se mando; el orden pedido si es valido
	 * @throws IllegalArgumentException si el campo o la direccion no son validos (400)
	 */
	public static Optional<OrdenDeListado> leer(String sort, Set<String> permitidos) {
		if (sort == null || sort.isBlank()) {
			return Optional.empty();
		}
		String[] partes = sort.strip().split(",", -1);
		if (partes.length != 2 || !permitidos.contains(partes[0])
				|| !("asc".equals(partes[1]) || "desc".equals(partes[1]))) {
			// No se repite el valor recibido: viene del cliente y reflejarlo es XSS reflejado.
			throw new IllegalArgumentException("El parametro sort admite un solo valor campo,asc "
					+ "o campo,desc, con campo entre: " + String.join(", ", ordenados(permitidos)));
		}
		return Optional.of(new OrdenDeListado(partes[0], "desc".equals(partes[1])));
	}

	/** Atajo para la lista blanca declarada como varargs. */
	public static Optional<OrdenDeListado> leer(String sort, String... permitidos) {
		return leer(sort, Set.of(permitidos));
	}

	/** {@code campo,asc} o {@code campo,desc}: la forma en la que viaja. */
	public String clave() {
		return campo + (descendente ? ",desc" : ",asc");
	}

	/**
	 * La clave para una consulta nativa que ordena con {@code CASE WHEN :orden = 'campo,asc'}.
	 * Sin orden pedido devuelve el texto vacio, que no coincide con ninguna rama.
	 */
	public static String claveDe(Optional<OrdenDeListado> orden) {
		return orden.map(OrdenDeListado::clave).orElse("");
	}

	public Sort.Direction direccion() {
		return descendente ? Sort.Direction.DESC : Sort.Direction.ASC;
	}

	/**
	 * El {@link Sort} de Spring Data para una consulta JPQL o derivada: la propiedad del campo y
	 * el desempate unico, en la misma direccion.
	 */
	public Sort aSort(String propiedad, String desempate) {
		return Sort.by(direccion(), propiedad).and(Sort.by(direccion(), desempate));
	}

	/**
	 * Aplica la direccion a un comparador ascendente del campo y le agrega el desempate. Para los
	 * listados que se paginan en memoria.
	 */
	public <T> Comparator<T> aplicar(Comparator<T> porCampo, Comparator<T> desempate) {
		Comparator<T> combinado = porCampo.thenComparing(desempate);
		return descendente ? combinado.reversed() : combinado;
	}

	private static List<String> ordenados(Set<String> permitidos) {
		return permitidos.stream().sorted().toList();
	}
}
