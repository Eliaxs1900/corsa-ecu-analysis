# Extractos de fuentes no descargables (preservados a mano, 22-jul-2026)

Contenido rescatado de páginas que no se pudieron archivar en crudo
(bloqueo Cloudflare, foros caídos o con login). Cada extracto conserva
lo técnicamente relevante para este proyecto.

## Fallo del EDU del 1.7 DI/DTI (ecutesting.com — bloqueada por Cloudflare)

Fuente: https://www.ecutesting.com/common-faults/vauxhall/vauxhall-17-tdi-ecuedu/

- Síntoma: el coche **se cala intermitentemente o no arranca**.
- Al leer averías, el código habitual es **P0251 "spill valve malfunction"**
  (válvula de derrame de la bomba), pero casi siempre el culpable es el
  **EDU**, no la válvula.
- Causa de diseño: la spill valve es un solenoide de altísima corriente y
  acaba destruyendo el driver del EDU.

## Reparación del EDU (qdi-ltd.co.uk — página retirada, 404)

Fuente: https://www.qdi-ltd.co.uk/qerauto/astra1.7dtiedu.html (+ documento
"Reparatie 1.7dti" en Scribd, requiere cuenta)

- ~90 % de los casos se arreglan con: **cambiar condensadores electrolíticos,
  repasar soldaduras frías** (especialmente en la inductancia del convertidor
  de 130 V) **y/o sustituir el FET** dañado.
- Referencias del EDU: Isuzu 8971891360 / -61 / -62 = Delphi 16267710
  (= GM 97189136 / 6237108).

## Direccionamiento KWP2000 (forums.openecu.org — foro inaccesible)

Fuente: http://forums.openecu.org/viewtopic.php?f=19&t=832

- Rango **0x10–0x17 reservado para ECUs de motor** (ECM).
- Hay fabricantes que usan **0x10 para gasolina y 0x11 para diésel** — cuadra
  con nuestro Y17DTL respondiendo en 0x11.
- Testers: rango **0xF0–0xFD**, típicamente **0xF1**; la ECU responde a la
  dirección de origen de la petición.

## Protocolo KW-82 (auto-diagnostics.info)

Fuente: https://www.auto-diagnostics.info/kw82_protocol

- KW-82 se usó en ECUs Opel de **1994 a 2004** (módulos de carrocería,
  airbag, inmovilizador, cuadro...), junto a ISO 9141, KW81 y KWP2000.
- OP-COM lo habla por los pines 3/7/8/12; **el ELM327 no lo soporta**
  (su firmware solo implementa J1850 PWM/VPW, ISO 9141-2, ISO 14230 y CAN).

## Referencias del ECM (auto24parts.com)

- ECU motor Y17DT/Y17DTI/**Y17DTL**: **Delphi/Delco 12212819 = Isuzu 8973065750**.

## Esquemas eléctricos oficiales (pendientes de cuenta/compra)

- **Opel TIS 2000**: manual de taller oficial con esquemas; mirrors online
  (kolhosniki.ru/tis2000, epcatalogs.com) o en eBay barato.
- PDF «opel-17dti-y17dt ECM diesel control unit wiring» (~558 KB) en
  elektroda.com (topic 474970) — requiere registro gratuito.
- Hilo con esquemas del Y17DT en mhhauto.com (Thread-corsa-c-y17dt-wiring-diagram) —
  requiere cuenta.
