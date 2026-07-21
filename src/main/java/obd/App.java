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
        System.out.println("Comandos: ports | open <COMx> | probe | dtc | clear | live | trace on|off | quit");
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
