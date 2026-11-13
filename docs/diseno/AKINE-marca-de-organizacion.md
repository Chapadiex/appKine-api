# Marca de la organización — colores institucionales, logo y pie de página

12/11/2026. Pedido del usuario: cada centro define sus colores para el tema claro y el oscuro, con
paletas sugeridas que puede editar libremente, y muestra su logo y sus datos de contacto.

## Qué se decidió

- **Dónde se configura:** pestaña "Marca" de Organización (`/organizacion/marca`), sólo con
  `tenant:update` (el `ORG_ADMIN`). Se edita cuando se quiera.
- **Qué elige el centro:** dos colores por tema. El *principal* pinta la acción (botones, foco,
  selección y hover); el *acento*, los enlaces y la pestaña activa. Un color sin elegir es el de AKINE.
- **Paletas:** diez sugeridas, todas medidas (principal ≥ 3:1 y acento ≥ 4,5:1 contra la superficie
  de su tema). Son punto de partida: cada color se cambia a mano, se aclara o se oscurece.
- **Legibilidad sin limitar:** el contraste **no se valida en el servidor**. La pantalla avisa y
  ofrece el tono más cercano que sí se lee, y deja guardar. El texto sobre el color principal
  (blanco o casi negro) lo elige la aplicación.
- **Lo que no cambia:** éxito, aviso, peligro y los estados del turno. Significan lo mismo en todos
  los centros.
- **Logo:** PNG, JPG o WebP de hasta 500 kB, uno por tema (sin el oscuro se usa el claro). El tipo lo
  deciden los bytes; SVG se rechaza porque puede traer script. Reemplaza la marca AKINE de la barra
  lateral.
- **Pie de página:** teléfono, email, sitio, dirección y una leyenda libre. Sólo se dibuja lo cargado.

## Enfoques considerados

1. **Variables CSS aplicadas en el cliente al entrar al contexto (elegido).** El backend guarda la
   marca; la aplicación pisa los tokens de `design-system.css` con una hoja construida
   (`adoptedStyleSheets`, que la CSP con nonce no bloquea).
2. Temas fijos sin edición: contradice el pedido.
3. Un CSS generado por centro en el backend: maquinaria de más para cuatro colores.

## Modelo y contrato (0.168.0, aditivo; migración V14)

- `organization_marca`: una fila por organización, con `version` propia. Se crea en la primera
  edición con `INSERT ... ON CONFLICT DO NOTHING`.
- `organization_logo`: un logo por organización y tema, en `bytea`. Se escribe con un upsert nativo.
  Es configuración, no historia: reemplazar pisa y quitar borra; el hecho queda en `audit_event`.
- `GET /organizations/{orgId}/marca` y `GET .../marca/logo/{variante}`: miembro vigente.
- `PUT .../marca`, `PUT` y `DELETE .../marca/logo/{variante}`: `tenant:update`.
- Eventos: `ORGANIZATION_BRANDING_UPDATED`, `ORGANIZATION_LOGO_UPDATED`, `ORGANIZATION_LOGO_REMOVED`.

## Fuera de esta entrega

- Correos con la marca del centro (segunda tanda).
- La pantalla de ingreso sigue con la marca AKINE: ahí todavía no se sabe de qué centro es la persona.
- Sin logo cargado la barra lateral sigue mostrando AKINE.

## Colores de interfaz por zona (12/11/2026)

Además del principal y el acento, cada tema admite seis colores opcionales de zona, agrupados en
`interfazClaro` e `interfazOscuro` (`ColoresDeInterfaz`): barra lateral, cabecera, fondo de página,
tarjetas, pie y botón secundario. Se guardan en doce columnas de `organization_marca`
(`color_<zona>_<tema>`), con el mismo CHECK `#rrggbb` en minúsculas.

- **Ausente significa "automático"**: el cliente deriva la zona del color principal (un tinte) o
  deja la de AKINE. El servidor no deriva ni valida contraste, igual que con los otros colores.
- **El texto sobre una zona lo elige el cliente** (blanco o casi negro) para barra lateral, cabecera
  y pie. Tarjetas y fondo no dan vuelta el texto: la pantalla avisa si no se lee.
- La migración sigue siendo `V14`: la rama no estaba mergeada cuando se agregaron las columnas.
- Los datos de contacto siguen en el mismo recurso, pero la pantalla los edita desde "Datos del
  centro"; como el `PUT` reemplaza la marca entera, cada pantalla reenvía lo que no edita.
