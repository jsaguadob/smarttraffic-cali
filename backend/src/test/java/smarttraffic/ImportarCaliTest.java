package smarttraffic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smarttraffic.datos.ImportarCali;
import smarttraffic.datos.RedVialJson;

class ImportarCaliTest {
    private static String correr(String[] args, int[] codigo) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        codigo[0] = ImportarCali.ejecutar(args, new PrintStream(b, true, StandardCharsets.UTF_8));
        return b.toString(StandardCharsets.UTF_8);
    }

    @Test @DisplayName("El comando importa desde archivos locales de Overpass y escribe la red que el servidor carga")
    void desdeArchivos() throws Exception {
        Path dir = Files.createTempDirectory("imp");
        String calles = """
            {"elements":[
              {"type":"node","id":1,"lat":3.4500,"lon":-76.5350},{"type":"node","id":2,"lat":3.4500,"lon":-76.5338,"tags":{"highway":"traffic_signals"}},
              {"type":"node","id":3,"lat":3.4500,"lon":-76.5326},{"type":"node","id":4,"lat":3.4512,"lon":-76.5338},
              {"type":"way","id":10,"nodes":[1,2,3],"tags":{"highway":"secondary","name":"Calle Uno"}},
              {"type":"way","id":11,"nodes":[2,4],"tags":{"highway":"residential","name":"Carrera Dos"}}
            ]}""";
        Files.writeString(dir.resolve("calles.json"), calles);
        Files.writeString(dir.resolve("servicios.json"), "{\"elements\":[{\"type\":\"node\",\"id\":9,\"lat\":3.4512,\"lon\":-76.5338,\"tags\":{\"amenity\":\"hospital\",\"name\":\"Hospital X\"}}]}");
        Files.writeString(dir.resolve("semaforos.csv"), "latitud,longitud\n3.45005,-76.53381\n3.4500,-76.5350\n");
        Path salida = dir.resolve("sub/red.json");
        int[] codigo = new int[1];
        String texto = correr(new String[] {"--calles", dir.resolve("calles.json").toString(), "--servicios", dir.resolve("servicios.json").toString(), "--semaforos", dir.resolve("semaforos.csv").toString(), "--salida", salida.toString()}, codigo);
        assertEquals(0, codigo[0], texto);
        assertTrue(texto.contains("Listo:") && texto.contains("OpenStreetMap"));
        assertTrue(texto.contains("Semáforos de los datos abiertos asignados a intersecciones: 2 de 2"), texto);
        var red = RedVialJson.leer(Files.readString(salida));
        assertEquals(4, red.red().intersecciones().size());
        assertNotNull(red.proyeccion());
        assertEquals(1, red.red().bases().size());
    }

    @Test @DisplayName("Errores claros: opciones desconocidas, cuadro mal escrito o demasiado grande, archivos que faltan")
    void errores() {
        int[] c = new int[1];
        assertTrue(correr(new String[] {"--raro", "1"}, c).contains("Opción desconocida")); assertEquals(1, c[0]);
        assertTrue(correr(new String[] {"--bbox"}, c).contains("Falta el valor")); assertEquals(1, c[0]);
        assertTrue(correr(new String[] {"--bbox", "1,2,3"}, c).contains("cuatro números"));
        assertTrue(correr(new String[] {"--bbox", "a,b,c,d"}, c).contains("no es un número"));
        assertTrue(correr(new String[] {"--bbox", "3.5,-76.5,3.4,-76.4"}, c).contains("sur < norte"));
        assertTrue(correr(new String[] {"--bbox", "3.0,-77.0,4.0,-76.0"}, c).contains("demasiado grande"));
        assertTrue(correr(new String[] {"--calles", "/no/existe.json"}, c).startsWith("Error:")); assertEquals(1, c[0]);
        assertTrue(correr(new String[] {"--calles", "/no/existe.json", "--overpass", "http://localhost:1"}, c).startsWith("Error:"));
    }
}
