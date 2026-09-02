package com.akine.person.domain.port;

import java.util.Optional;

/**
 * El almacenamiento del CONTENIDO de un adjunto, del lado del dominio.
 *
 * <h2>Por que hay un puerto y no un {@code LONGBLOB}</h2>
 *
 * <p>Los motivos de que el binario no entre a la base estan en la cabecera de {@code V40}. Lo que
 * este puerto agrega es la consecuencia de diseño: <b>el dia que esto corra contra almacenamiento
 * de objetos, migrar es escribir otro adaptador</b>, y ninguna regla de negocio se entera. Hoy el
 * unico adaptador escribe en el sistema de archivos local, que es lo que la etapa pide
 * explicitamente ("adaptador local seguro inicial").
 *
 * <h2>Por que {@code byte[]} y no {@code InputStream}</h2>
 *
 * <p>Porque el tamano ya esta acotado antes de llegar aca —10 MB por defecto— y porque el
 * contenido hay que recorrerlo entero de todos modos para calcular su SHA-256 y detectar su tipo
 * real. Un {@code InputStream} que hay que consumir dos veces obliga a bufferearlo, o sea a tener
 * el arreglo igual, con la diferencia de que el ciclo de vida del stream queda repartido entre
 * capas. Si algun dia se admiten archivos grandes, la firma cambia y el compilador va a nombrar
 * todos los lugares a revisar.
 *
 * <h2>La clave es opaca y la ruta la deriva el adaptador</h2>
 *
 * <p>{@code storageKey} no lleva el nombre del archivo, ni la organizacion, ni la extension. El
 * adaptador es el unico que sabe donde cae. RN-M25-002 pide no exponer rutas internas; esto
 * ademas garantiza que <b>ninguna cadena de origen externo participe de un path</b>.
 */
public interface AdjuntoStoragePort {

	/**
	 * Escribe el contenido bajo esa clave.
	 *
	 * @throws java.io.UncheckedIOException si el almacenamiento no pudo aceptarlo. El llamador lo
	 *                                      deja propagar: la transaccion revierte y no queda fila
	 *                                      apuntando a un contenido que no existe
	 */
	void guardar(String storageKey, byte[] contenido);

	/** El contenido, o vacio si el almacenamiento no lo tiene. */
	Optional<byte[]> leer(String storageKey);

	/**
	 * Tope de bytes que este almacenamiento acepta por archivo.
	 *
	 * <p>Vive en el puerto y no en una {@code @ConfigurationProperties} inyectada en el servicio
	 * por una razon de arquitectura y no de estilo: {@code application} <b>no puede importar
	 * {@code infrastructure}</b> —ArchUnit lo verifica— y el valor es configuracion, que es
	 * infraestructura por definicion. Ademas es conceptualmente correcto: el tope es una propiedad
	 * del almacenamiento, y el dia que haya un adaptador contra almacenamiento de objetos con otro
	 * limite, la regla de negocio no cambia.
	 */
	long tamanoMaximo();
}
