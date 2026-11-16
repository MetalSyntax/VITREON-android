# Vitreon web (React) → Vitreon Kotlin: especificación y errores corregidos

Fuente: `~/Documents/GitHub/VITREON` (v1.3.0/1.4.0). Inventario hecho el 2026-11-16 leyendo el código.

## Modelo
- **Note**: id, title, content (Markdown o checklist `[ ] `/`[x] `), category, isPinned, isArchived, isLocked,
  isChecklist, tags[], attachments {id, type image|drawing|voice, data}, order, createdAt, updatedAt, deletedAt (papelera), importId.
- **Category**: {id, name, color (nombre Tailwind), icon (Material Symbol)}. Por defecto: work/blue/work,
  personal/emerald/person, ideas/amber/lightbulb, travel/purple/flight, fitness/rose/fitness_center,
  uncategorized/slate/description (no se borra).
- Colores: blue #3b82f6, emerald #10b981, amber #f59e0b, purple #a855f7, rose #f43f5e, slate #64748b, cyan #06b6d4,
  orange #f97316, pink #ec4899, lime #84cc16, indigo #6366f1, violet #8b5cf6, fuchsia #d946ef, teal #14b8a6, red #ef4444, yellow #eab308.
- Exportación JSON: `{app:"VitreonNotes", version, exportedAt, notes:[...]}` (la web NO incluía categorías).
- `.vitreon` web: `{"__vitreon_encrypted__":true, iv, data}` con una clave fija compilada en la app (inseguro).

## Funciones (todas portadas)
Inicio: búsqueda con espera 300 ms, búsqueda por voz, orden (recientes/antiguas/A-Z/Z-A), vista cuadrícula/lista/tarjeta,
chips de categoría, sección Fijadas, Favoritas/Archivo/Papelera (30 días), vaciar papelera, arrastrar para ordenar,
modo selección (pulsación larga) con fijar/categoría/etiquetas/exportar/archivar/borrar.
Editor: modo lectura/edición, barra (negrita, cursiva, ++subrayado++, tachado, H1-H3, cita, lista, código, ---, checklist),
imágenes/cámara/voz/dibujo, categoría, etiquetas, bloquear/archivar/borrar. Categorías con 16 colores y 70 íconos.
Ajustes: tema claro/oscuro/negro, idioma es/en/pt, exportar md/json/vitreon, importar. Perfil: foto, nombre, correo, bio.

## Estilo
Fondo oscuro #030712 con degradado #1e1b4b, vidrio rgba(15,23,42,.4) y borde rgba(255,255,255,.05); negro #000;
claro #f8fafc. Acento índigo #6366f1, FAB #6366f1→#4f46e5→#9333ea. Texto #f1f5f9 / #94a3b8. Radios: tarjetas 32,
buscador 28, barra inferior 40, modales 24. Fuente Outfit (en Kotlin: Inter, la misma de Parity).

## Errores de la web y cómo quedan en Kotlin
| Web | Kotlin |
|---|---|
| XSS: enlaces `javascript:` y comillas sin escapar en RichText | Markdown propio sin HTML; enlaces solo http/https/mailto |
| Clave de cifrado compilada en la app ("undefined" si falta .env) | Clave por dispositivo en Android Keystore (cifrado de sobre, como Parity) |
| Solo título y contenido cifrados; adjuntos, etiquetas y PIN en claro | Archivo completo cifrado; adjuntos como archivos cifrados |
| Nota bloqueada = PIN de 4 dígitos en claro, sin límite de intentos | PIN de app con hash PBKDF2 + bloqueo tras 5 intentos; contenido de la nota bloqueada cifrado aparte |
| Importar pisa notas locales más nuevas sin avisar | Conflicto si la local es más nueva (mantener / reemplazar / ambas) |
| Atrás/Esc descarta lo escrito | Autoguardado al salir del editor |
| Huella con WebAuthn que no verifica nada | BiometricPrompt |
| Dedup por título+fecha casi nunca coincide | Huella de contenido guardada en cada nota |
| Exportación sin categorías | Exportación con categorías |
| Borrar categoría deja notas huérfanas | Pasan a «Sin categoría» |
| Id de categoría = slug (choca y sobrescribe) | uuid |
| Fijar/archivar/bloquear cierran el editor | No lo cierran |
| Idioma no cambia hasta recargar | Estado compartido, cambia al momento |
| Búsqueda ignora categoría y etiquetas | Busca en título, contenido y etiquetas, respetando el filtro |
| «Antiguas» ordena por edición | Ordena por creación |
| Seleccionar todo omite fijadas | Incluye fijadas |
| Cada ítem de lista en su propio `<ul>`; formato dentro de `código` | Parser por bloques; el código no se formatea |
| Exportar .md incluye la papelera y nombres sin limpiar | Excluye papelera, nombres saneados, todo en un .zip |
