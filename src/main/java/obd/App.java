package obd;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Consola de diagnóstico OBD para el Vgate iCar2 BT3.0 (ELM327 por COM Bluetooth).
 * Objetivo: Opel Corsa C 1.7 diésel (2003) — ver {@link Sonda} para la estrategia
 * de detección de protocolo.
 *
 * Uso: java -jar obd-tools.jar [COMx]
 */
public final class App {

    private Elm327 elm;
    private Kwp kwp;

    public static void main(String[] args) throws Exception {
        prepararDllNativa();
        new App().ejecutar(args.length > 0 ? args[0] : null);
    }

    /**
     * jSerialComm prueba las arquitecturas en orden aarch64→x86_64 extrayendo la
     * DLL a un temporal de solo lectura; si el borrado intermedio falla (p. ej.
     * bajo un sandbox) nunca llega a probar la correcta. Extraemos nosotros la
     * DLL x86_64 del propio jar y usamos jSerialComm.library.path, que se
     * consulta antes que el mecanismo de extracción.
     */
    private static void prepararDllNativa() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win") || !arch.contains("64") || arch.contains("aarch")) return;
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("lib", "Windows", "x86_64");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path dll = dir.resolve("jSerialComm.dll");
            if (!java.nio.file.Files.exists(dll)) {
                try (var in = App.class.getResourceAsStream("/Windows/x86_64/jSerialComm.dll")) {
                    if (in == null) return; // recurso no empaquetado: que decida jSerialComm
                    java.nio.file.Files.copy(in, dll);
                }
            }
            System.setProperty("jSerialComm.library.path",
                    dir.getParent().getParent().toAbsolutePath().toString());
        } catch (IOException e) {
            System.out.println("[aviso] No se pudo preparar la DLL nativa: " + e.getMessage());
        }
    }

    private void ejecutar(String puertoInicial) throws Exception {
        System.out.println("== corsa-obd-tools :: consola ELM327 ==");
        System.out.println("Comandos EOBD:  ports | open <COMx> | probe | dtc | clear | live");
        System.out.println("Comandos Opel:  opel | oid | odtc | oclear si | o21 <id> | kwp <hex...>");
        System.out.println("Otros:          trace on|off | quit");
        System.out.println("Cualquier otra entrada se envía tal cual al adaptador (AT... o peticiones hex).");

        if (puertoInicial != null) abrirPuerto(puertoInicial);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            while (true) {
                String linea;
                try {
                    linea = reader.readLine("obd> ");
                } catch (UserInterruptException | EndOfFileException e) {
                    break;
                }
                linea = linea.trim();
                if (linea.isEmpty()) continue;
                try {
                    if (!procesar(linea)) break;
                } catch (IOException e) {
                    System.out.println("[error] " + e.getMessage());
                }
            }
        } finally {
            if (elm != null) elm.close();
        }
        System.out.println("Hasta luego.");
    }

    /** Devuelve false para salir del bucle. */
    private boolean procesar(String linea) throws IOException {
        String[] partes = linea.split("\\s+", 2);
        switch (partes[0].toLowerCase(Locale.ROOT)) {
            case "quit", "exit" -> { return false; }
            case "ports" -> listarPuertos();
            case "open" -> {
                if (partes.length < 2) System.out.println("Uso: open COM5");
                else abrirPuerto(partes[1]);
            }
            case "probe" -> probar();
            case "dtc" -> leerDtc();
            case "clear" -> borrarDtc(partes.length > 1 ? partes[1] : null);
            case "live" -> datosEnVivo();
            case "opel" -> {
                if (requiereAdaptador()) {
                    sesionKwp().init();
                    System.out.println("Sesión KWP2000 abierta con la ECU del motor (0x11).");
                    System.out.println("VIN: " + sesionKwp().identificacion(0x90));
                }
            }
            case "oid" -> {
                if (requiereAdaptador()) {
                    int[] opciones = {0x90, 0x91, 0x92, 0x94, 0x95, 0x97, 0x98, 0x9A};
                    for (int op : opciones) {
                        try {
                            System.out.printf("  1A %02X: %s%n", op, sesionKwp().identificacion(op));
                        } catch (IOException e) {
                            System.out.printf("  1A %02X: [%s]%n", op, e.getMessage());
                        }
                    }
                }
            }
            case "odtc" -> {
                if (requiereAdaptador()) {
                    List<Kwp.Dtc> dtcs = sesionKwp().leerDtc();
                    if (dtcs.isEmpty()) {
                        System.out.println("La ECU no informa de ningún DTC. Motor limpio.");
                    } else {
                        for (Kwp.Dtc d : dtcs) {
                            System.out.printf("  %s  estado=%02X%s%n", d.codigo(), d.estado(),
                                    d.activo() ? "  [ACTIVO]" : "");
                        }
                    }
                }
            }
            case "oclear" -> {
                if (!requiereAdaptador()) break;
                if (partes.length > 1 && partes[1].equalsIgnoreCase("si")) {
                    System.out.println(sesionKwp().borrarDtc()
                            ? "DTCs borrados por la ECU (respuesta 54)."
                            : "La ECU no confirmó el borrado.");
                } else {
                    System.out.println("Borra los DTC de la ECU del motor. Escribe 'oclear si' para confirmar.");
                }
            }
            case "o21" -> {
                if (!requiereAdaptador()) break;
                if (partes.length < 2) { System.out.println("Uso: o21 <id hex>, p. ej. o21 01"); break; }
                List<Integer> data = sesionKwp().datosLocales(Integer.parseInt(partes[1], 16));
                System.out.println("  " + hex(data));
            }
            case "o21w" -> {
                if (!requiereAdaptador()) break;
                String[] a = partes.length > 1 ? partes[1].split("\\s+") : new String[0];
                if (a.length == 0) { System.out.println("Uso: o21w <id hex> [muestras=6] [segundos=15]"); break; }
                vigilarDatos(Integer.parseInt(a[0], 16),
                        a.length > 1 ? Integer.parseInt(a[1]) : 6,
                        a.length > 2 ? Integer.parseInt(a[2]) : 15);
            }
            case "kwp" -> {
                if (!requiereAdaptador()) break;
                if (partes.length < 2) { System.out.println("Uso: kwp 18 00 FF 00"); break; }
                String[] tokens = partes[1].trim().split("\\s+");
                int[] datos = new int[tokens.length];
                for (int i = 0; i < tokens.length; i++) datos[i] = Integer.parseInt(tokens[i], 16);
                System.out.println("  " + hex(sesionKwp().peticion(datos)));
            }
            case "trace" -> {
                boolean on = partes.length > 1 && partes[1].equalsIgnoreCase("on");
                conAdaptador(e -> e.setTraza(on));
                System.out.println("Traza " + (on ? "activada" : "desactivada"));
            }
            default -> {
                if (requiereAdaptador()) {
                    String resp = elm.send(linea.toUpperCase(Locale.ROOT), Elm327.TIMEOUT_INIT_MS);
                    System.out.println(resp.isEmpty() ? "(sin respuesta)" : resp);
                }
            }
        }
        return true;
    }

    private void listarPuertos() {
        List<String> puertos = Elm327.puertosDisponibles();
        if (puertos.isEmpty()) {
            System.out.println("No hay puertos COM. Empareja el iCar2 (PIN típico: 1234) y reintenta.");
        } else {
            puertos.forEach(p -> System.out.println("  " + p));
            System.out.println("Nota: con Bluetooth SPP suele haber dos COM; el que funciona es el SALIENTE.");
        }
    }

    private void abrirPuerto(String nombre) throws IOException {
        if (elm != null) elm.close();
        elm = Elm327.abrir(nombre);
        kwp = null; // la sesión KWP anterior muere con el puerto
        System.out.println("Puerto " + nombre + " abierto. Identificando adaptador...");
        String id = Sonda.inicializarAdaptador(elm);
        System.out.println("Adaptador: " + id);
    }

    private void probar() throws IOException {
        if (!requiereAdaptador()) return;
        System.out.println("Detectando protocolo (con el contacto puesto)... puede tardar hasta un minuto.");
        Sonda.Resultado r = Sonda.detectar(elm);
        System.out.println("Protocolo: " + r.protocolo());
        System.out.println(r.detalle());
    }

    private void leerDtc() throws IOException {
        if (!requiereAdaptador()) return;
        String n = elm.send("0101", Elm327.TIMEOUT_INIT_MS); // nº de DTCs + estado MIL
        System.out.println("Estado (0101): " + n.replace('\n', ' '));
        String resp = elm.send("03", Elm327.TIMEOUT_INIT_MS);
        List<String> codigos = ObdParser.decodificarDtc(resp);
        if (codigos.isEmpty()) {
            System.out.println("Sin DTCs almacenados (o respuesta no estándar): " + resp.replace('\n', ' '));
        } else {
            System.out.println("DTCs: " + String.join(", ", codigos));
        }
    }

    private void borrarDtc(String confirmacion) throws IOException {
        if (!requiereAdaptador()) return;
        if (!"si".equalsIgnoreCase(confirmacion)) {
            System.out.println("ATENCIÓN: el modo 04 borra DTCs y datos congelados. Escribe 'clear si' para confirmar.");
            return;
        }
        String resp = elm.send("04", Elm327.TIMEOUT_INIT_MS);
        System.out.println(resp.contains("44") ? "DTCs borrados (respuesta 44)." : "Respuesta: " + resp);
    }

    private void datosEnVivo() throws IOException {
        if (!requiereAdaptador()) return;
        for (Map.Entry<String, String> pid : ObdParser.pidsBasicos().entrySet()) {
            try {
                String resp = elm.send(pid.getKey());
                List<Integer> datos = ObdParser.bytesDeDatos(resp, "41");
                System.out.printf("  %-32s %s%n", pid.getValue() + ":",
                        ObdParser.interpretarPid(pid.getKey(), datos));
            } catch (IOException e) {
                System.out.printf("  %-32s [error] %s%n", pid.getValue() + ":", e.getMessage());
            }
        }
    }

    /**
     * Muestrea el bloque 21 <id> varias veces y enseña qué offsets cambian:
     * la forma empírica de mapear campos sin documentación (temperaturas al
     * enfriarse el motor, rpm al acelerar, etc.).
     */
    private void vigilarDatos(int id, int muestras, int segundos) throws IOException {
        List<List<Integer>> capturas = new java.util.ArrayList<>();
        System.out.printf("Muestreando 21 %02X: %d capturas cada %d s...%n", id, muestras, segundos);
        for (int i = 0; i < muestras; i++) {
            try {
                List<Integer> data = sesionKwp().datosLocales(id);
                capturas.add(data.subList(2, data.size())); // sin el prefijo 61 <id>
                System.out.printf("  captura %d/%d (%d bytes)%n", i + 1, muestras, data.size() - 2);
            } catch (IOException e) {
                // El arranque del motor hunde la tensión y puede tirar la sesión: reintentar
                System.out.printf("  captura %d/%d perdida (%s), reintentando init...%n",
                        i + 1, muestras, e.getMessage());
                try { sesionKwp().init(); } catch (IOException e2) {
                    System.out.println("  re-init fallido: " + e2.getMessage());
                }
            }
            if (i < muestras - 1) {
                try { Thread.sleep(segundos * 1000L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (capturas.isEmpty()) {
            System.out.println("Sin capturas válidas.");
            return;
        }
        List<Integer> primera = capturas.get(0);
        System.out.println("Primera captura (offset: valor):");
        for (int i = 0; i < primera.size(); i += 16) {
            StringBuilder sb = new StringBuilder(String.format("  %02d:", i));
            for (int j = i; j < Math.min(i + 16, primera.size()); j++) {
                sb.append(String.format(" %02X", primera.get(j)));
            }
            System.out.println(sb);
        }
        System.out.println("Offsets que cambian entre capturas:");
        boolean alguno = false;
        for (int off = 0; off < primera.size(); off++) {
            StringBuilder serie = new StringBuilder();
            boolean cambia = false;
            for (List<Integer> c : capturas) {
                int v = off < c.size() ? c.get(off) : -1;
                serie.append(String.format("%02X ", v));
                if (v != primera.get(off)) cambia = true;
            }
            if (cambia) {
                alguno = true;
                System.out.printf("  offset %02d: %s%n", off, serie.toString().trim());
            }
        }
        if (!alguno) System.out.println("  (ninguno: el bloque está completamente estático)");
    }

    private Kwp sesionKwp() {
        if (kwp == null) kwp = new Kwp(elm);
        return kwp;
    }

    private static String hex(List<Integer> bytes) {
        StringBuilder sb = new StringBuilder();
        for (int b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    private boolean requiereAdaptador() {
        if (elm == null) {
            System.out.println("No hay puerto abierto. Usa 'ports' y luego 'open COMx'.");
            return false;
        }
        return true;
    }

    private void conAdaptador(java.util.function.Consumer<Elm327> accion) {
        if (requiereAdaptador()) accion.accept(elm);
    }
}
