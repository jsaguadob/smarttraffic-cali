package smarttraffic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smarttraffic.datos.ConexionBd;

class ConexionBdTest {

    @Test @DisplayName("La línea de Neon se convierte a JDBC: usuario y clave aparte, sin channel_binding, cifrada")
    void neon() {
        var d = ConexionBd.interpretar("postgresql://neondb_owner:npg_Abc123XyZ@ep-cool-name-123456.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require", null, null);
        assertEquals("jdbc:postgresql://ep-cool-name-123456.us-east-2.aws.neon.tech:5432/neondb?connectTimeout=10&sslmode=require", d.jdbcUrl());
        assertEquals("neondb_owner", d.usuario());
        assertEquals("npg_Abc123XyZ", d.clave());
        assertFalse(d.jdbcUrl().contains("channel_binding"));
        assertFalse(d.jdbcUrl().contains("npg_"), "la contraseña no va dentro de la dirección");
    }

    @Test @DisplayName("Con el prefijo postgres://, con puerto, y sin sslmode fuera de tu computador se exige cifrado")
    void variantes() {
        var d = ConexionBd.interpretar("postgres://u:p@db.ejemplo.com:6543/miapp", null, null);
        assertEquals("jdbc:postgresql://db.ejemplo.com:6543/miapp?connectTimeout=10&sslmode=require", d.jdbcUrl());
        var local = ConexionBd.interpretar("postgresql://u:p@localhost/prueba", null, null);
        assertEquals("jdbc:postgresql://localhost:5432/prueba?connectTimeout=10", local.jdbcUrl(), "en local no se fuerza el cifrado");
        assertTrue(ConexionBd.interpretar("postgresql://u:p@127.0.0.1/x?sslmode=disable", null, null).jdbcUrl().endsWith("&sslmode=disable"));
    }

    @Test @DisplayName("Contraseñas con caracteres codificados se decodifican bien; el «+» sigue siendo «+»")
    void codificados() {
        var d = ConexionBd.interpretar("postgresql://usuario:p%40ss%2Fw%3Ard+x@h.example.com/b", null, null);
        assertEquals("p@ss/w:rd+x", d.clave());
    }

    @Test @DisplayName("El usuario y la clave indicados aparte tienen prioridad sobre los de la línea")
    void prioridad() {
        var d = ConexionBd.interpretar("postgresql://viejo:vieja@h.example.com/b", "nuevo", "nueva");
        assertEquals("nuevo", d.usuario()); assertEquals("nueva", d.clave());
        var parcial = ConexionBd.interpretar("postgresql://viejo:vieja@h.example.com/b", null, "soloclave");
        assertEquals("viejo", parcial.usuario()); assertEquals("soloclave", parcial.clave());
    }

    @Test @DisplayName("Una dirección JDBC pasa tal cual (H2 en desarrollo, PostgreSQL ya convertido)")
    void jdbc() {
        var d = ConexionBd.interpretar("jdbc:h2:mem:x;MODE=PostgreSQL", "sa", "");
        assertEquals("jdbc:h2:mem:x;MODE=PostgreSQL", d.jdbcUrl()); assertEquals("sa", d.usuario());
        assertEquals("jdbc:postgresql://h/b?sslmode=require", ConexionBd.interpretar("jdbc:postgresql://h/b?sslmode=require", "u", "p").jdbcUrl());
    }

    @Test @DisplayName("Errores claros y nunca con la contraseña dentro del mensaje")
    void errores() {
        for (String mala : new String[] {null, "  ", "mysql://u:p@h/b", "postgresql://u:SECRETA@/b", "postgresql://u:SECRETA@h.example.com", "postgresql://u:SECRETA@h.example.com/b?sslmode=raro", "postgresql://u:SEC RETA@h.example.com/b"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ConexionBd.interpretar(mala, null, null), String.valueOf(mala));
            assertFalse(e.getMessage().contains("SECRETA"), "el mensaje no debe revelar la contraseña: " + e.getMessage());
        }
    }

    @Test @DisplayName("La descripción para el registro no incluye usuario ni contraseña")
    void descripcion() {
        var d = ConexionBd.interpretar("postgresql://neondb_owner:npg_Secreta@ep-x.us-east-2.aws.neon.tech/neondb?sslmode=require", null, null);
        assertEquals("jdbc:postgresql://ep-x.us-east-2.aws.neon.tech:5432/neondb", d.descripcion());
        assertFalse(d.descripcion().contains("neondb_owner") || d.descripcion().contains("Secreta"));
    }
}
