# Referencias técnicas encontradas en red (22-jul-2026)

## Asignación de pines del conector OBD en Opel (confirma nuestra foto)

Según [PinoutGuide — Opel OBD-II diagnostic connector](https://pinoutguide.com/CarElectronics/opel_obd2_diag_pinout.shtml):

| Pin | Señal (Opel) | En nuestro Corsa |
|---|---|---|
| 3 | K-line: radio, caja automática, **inmovilizador**, volante | ✔ poblado |
| 4/5 | Masas | ✔ |
| 7 | K-line: **motor**, (ABS, airbag, navegación en otros modelos) | ✔ — validado: solo motor responde |
| 8 | K-line: **TID/MID (display multifunción)** | ✔ poblado |
| 12 | K-line: TID/MID, **airbag**, aire acondicionado, (ABS) | ✔ poblado |
| 16 | +12V batería | ✔ |

→ El display con el sensor de temperatura muerto se diagnostica por el **pin 8**,
y el airbag por el **pin 12**. El ELM327 solo cablea el pin 7 internamente.

## Protocolo de los módulos viejos: KW-82

- [KW-82](https://www.auto-diagnostics.info/kw82_protocol) se usó en ECUs Opel
  de **1994 a 2004** (junto a ISO 9141, KW81 y KWP2000). OP-COM habla todos
  ellos por los pines 3/7/8/12.
- El ELM327 **no soporta KW81/KW82** (su datasheet solo cubre J1850, ISO
  9141-2, ISO 14230 y CAN). Un puente de pin 7→8/12 solo funcionaría si el
  módulo en cuestión habla KWP2000/ISO; si habla KW82, hace falta OP-COM.

## Direccionamiento KWP2000 (confirma nuestros hallazgos)

Según [openecu.org](http://forums.openecu.org/viewtopic.php?f=19&t=832):
- Rango **0x10–0x17 reservado para ECUs de motor**; hay fabricantes que usan
  0x10 para gasolina y **0x11 para diésel** ← exactamente nuestro caso.
- Testers: rango 0xF0–0xFD, típicamente **0xF1** ← el que usamos.

## Hardware del Y17DTL (identificado por referencias)

- **ECM (ECU del motor): Delphi/Delco 12212819 = Isuzu 8973065750**
  ([auto24parts](https://auto24parts.com/en_GB/p/ECU-ENGINE-CONTROLLER-OPEL-ASTRA-1.7-DT-DTI-Y17DT-Y17DTI-Y17DTL-DELPHI-DELCO-12212819-ISUZU-8973065750/9784)) — no es Denso
  (eso es el Z17DTH posterior).
- **EDU (módulo de control de la bomba inyectora): Isuzu 8971891360/-61/-62
  = Delphi 16267710** — montado aparte del ECM.
- Nota de identificación: el coche es el **1.7 DI (Y17DTL, 65 CV)**, no el
  DTI (Y17DT, 75 CV con intercooler). Misma familia Isuzu; el ECM Delphi y
  el EDU de las referencias cubren Y17DT/Y17DTI/Y17DTL indistintamente.

### ⚠️ Avería típica conocida del 1.7 DI/DTI: el EDU

Documentada en [ecutesting](https://www.ecutesting.com/common-faults/vauxhall/vauxhall-17-tdi-ecuedu/),
[QDI](https://www.qdi-ltd.co.uk/qerauto/astra1.7dtiedu.html) y
[ecuconnection](https://ecuconnection.co.uk/product/vauxhall-astra-corsa-combo-1-7-y17dt-diesel-pump-edu-repair-service/):
- **Síntomas**: paradas intermitentes del motor o no arranca.
- **DTC típico**: **P0251** (válvula de derrame / spill valve) — pero la culpable
  suele ser la electrónica del EDU, no la válvula.
- **Causa**: condensadores electrolíticos secos, soldaduras frías en la
  inductancia del convertidor de 130 V, o FET quemado (la spill valve consume
  muchísimo y castiga el driver).
- **Reparación**: recondensar + repasar soldaduras (~90 % de los casos, según
  [foros de reparación](https://www.scribd.com/document/334074649/Reparatie-1-7dti-1)).

→ Si algún día el coche se cala en marcha o aparece P0251 en `odtc`,
   mirar el EDU antes de tocar la bomba.

## Esquemas eléctricos completos

- **Opel TIS 2000** (el manual de taller oficial con esquemas) circula en
  mirrors online, p. ej. [kolhosniki.ru/tis2000](https://www.kolhosniki.ru/tis2000&dir=wd&base=1070g&model=18&year=36&engine=112)
  y recopilaciones tipo [epcatalogs](https://www.epcatalogs.com/Opel_tis/).
- PDF citado en [elektroda](https://www.elektroda.com/rtvforum/topic474970.html):
  «opel-17dti-y17dt ECM diesel control unit wiring» (~558 KB, requiere cuenta).
- Hilo con esquemas Y17DT en [MHH Auto](https://mhhauto.com/Thread-corsa-c-y17dt-wiring-diagram) (requiere cuenta).
