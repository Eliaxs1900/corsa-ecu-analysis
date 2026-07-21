# Plan de pruebas dinámicas — sesión con motor y maniobras

**Requisito**: zona segura sin tráfico donde se pueda maniobrar y parar a
voluntad (explanada, polígono vacío, aparcamiento grande). Con luz de día.

## Objetivos (en orden de valor)

1. **Confirmar la teoría del termostato**: ver el offset 41 subir desde frío
   hasta ~0xB0 (88 °C) y quedarse clavado, con el 38 inmóvil en 0x80.
2. **Destapar los canales de carga** (offsets 45–58): presión de turbo,
   caudalímetro y cantidad inyectada. El esquema Haynes garantiza que los
   sensores existen (presión turbo=41, barométrico=60, MAF=39 del diagrama).
3. **Encontrar la velocidad del vehículo** (aún sin localizar en el bloque).
4. **Tabla de marchas por ratio rpm/velocidad** (la caja no tiene sensor:
   la marcha se deduce del cociente).
5. Verificar el candidato **temperatura de combustible** (offsets 40–41 del
   bloque… el que sube lentamente) — con el motor trabajando, el gasoil de
   retorno se calienta bastante más deprisa que parado.
6. Antes/después: registros `A5–B3` y averías (`odtc`).

## Reglas de seguridad (innegociables)

- **Jamás tocar el portátil con el coche en movimiento.** Cada fase se lanza
  PARADO, se conduce, y se vuelve a parar. El registrador (`olog`) graba solo.
- Portátil asegurado: en el asiento del copiloto con su cinturón abrochado o
  en el suelo con la alfombra, donde no vuele en un frenazo.
- Bluetooth alcanza de sobra dentro del coche; no hace falta mirarlo.
- Si algo huele raro (testigo nuevo, tirones), se aborta y se lee `odtc`.
- El comando `oclear` NO se usa en esta sesión (no borrar nada antes de
  entender qué hay).

## Preparación en casa (antes de salir)

```
git pull            # por si acaso
mvn -q package      # jar al día (incluye el comando olog nuevo)
```
Portátil **cargado y enchufable** (mechero/inversor si hay). El coche llegará
con el motor caliente: **los primeros 10 minutos de la Fase 1 son los únicos
que piden motor frío**, así que idealmente hacer la sesión con el coche sin
arrancar desde hace horas y la zona segura cerca de casa… o asumir que la
Fase 1 se hace otro día desde frío en el aparcamiento de casa.

## Desarrollo — cada fase empieza y acaba con el coche PARADO

Consola: `java -jar target/obd-tools.jar COM8`

### Fase 0 — Línea base (parado, contacto, motor aún sin arrancar)
```
opel
odtc                  ← estado de averías de partida
o21 01                ← instantánea fría
oscan                 ← valores A5–B3 de partida (anotar)
ATRV
```

### Fase 1 — Arranque y calentamiento (parado, al ralentí)
```
olog 01 600           ← 10 min grabando
```
Arrancar el motor **con el registro ya corriendo** (capturará el cranking:
caída de tensión + primer encendido de flags). Dejar al ralentí sin tocar
nada. Qué debe verse: offset 41 subiendo poco a poco; 38 quieto; tensión
saltando a ~0x38; rpm ~850.

### Fase 2 — Escalones de rpm (parado, punto muerto)
```
olog 01 180
```
Con el registro corriendo: mantener ~5 s en cada escalón según el
cuentarrevoluciones: **ralentí → 1500 → 2000 → 2500 → 3000 → ralentí**.
Calibra la escala de rpm en todo el rango y muestra qué canales de carga
responden a rpm sin carga (poca inyección, casi nada de turbo).

### Fase 3 — Marchas a rpm fija (rodando, suave)
```
olog 01 240
```
Conducir tranquilo manteniendo **~2000 rpm en 1ª, luego 2ª, luego 3ª**
(unos 10–15 s por marcha, velocidad estable), y parar. El canal que escale
~proporcional entre marchas a rpm constante es la **velocidad**; el cociente
rpm/velocidad da la tabla de marchas.

### Fase 4 — Carga real: turbo y caudalímetro (rodando)
```
olog 01 240
```
Dos o tres **aceleraciones francas en 2ª de ~1500 a ~3500 rpm** (pedal a
fondo, sin miedo: el flag de plena carga ya lo conocemos), con recuperación
suave entre ellas. Aquí es donde el turbo sopla de verdad: buscar el canal
que sube con retardo respecto al pedal (presión) y el que sigue al aire (MAF).

### Fase 5 — Retención e inyección cero (rodando)
```
olog 01 120
```
Acelerar a ~3000 rpm en 2ª/3ª y **soltar el pedal del todo dejando retener**
hasta casi el ralentí (cut-off: la inyección se corta con el coche empujando
el motor). Los canales de inyección deben irse a cero con rpm altas — la
firma perfecta para distinguir inyección de aire.

### Fase 6 — Cierre (parado)
```
o21 01                ← instantánea caliente
oscan                 ← ¿han cambiado A5–B3?
odtc                  ← ¿alguna avería nueva tras la paliza?
ATRV
quit
```

## Después (en casa, conmigo)

1. Traer los CSV de `logs/` — los analizo correlando cada maniobra con las
   series (los timestamps + tu memoria de la secuencia bastan).
2. Actualizar `mapa-21-01.md` con los canales nuevos.
3. Commit + push de logs, mapa y conclusiones.

## Criterios de éxito

- [ ] 41 llegó a ~0xB0 y se estabilizó con 38 inmóvil (teoría cerrada)
- [ ] Canal de velocidad identificado con su escala
- [ ] Presión de turbo identificada (sube con carga, retardo vs pedal)
- [ ] MAF/inyección distinguidos (cut-off de la Fase 5)
- [ ] Tabla de ratios de marchas (1ª–3ª mínimo)
- [ ] Sin DTCs nuevos al acabar
