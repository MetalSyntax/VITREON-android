# Sincronizar Vitreon con Google Drive

Estado actual: **no implementado** (está en `PENDIENTES.md`). La app no declara el permiso `INTERNET` ni usa Google Play Services. Este documento deja los pasos de Google y lo que falta en el código. El OAuth no se implementó aquí.

## 1. Proyecto en Google Cloud

1. Entra a <https://console.cloud.google.com> y crea un proyecto (por ejemplo `vitreon`). Puedes reutilizar el de Parity.
2. **APIs y servicios > Biblioteca > Google Drive API > Habilitar.**

## 2. Pantalla de consentimiento OAuth

1. **APIs y servicios > Pantalla de consentimiento de OAuth** (en consolas nuevas: *Google Auth Platform > Branding / Público*).
2. Tipo de usuario: **Externo** (con cuenta personal no hay otra opción).
3. Nombre de la app `Vitreon`, correo de soporte y de contacto del desarrollador.
4. **Acceso a datos (Scopes) > Agregar:** `https://www.googleapis.com/auth/drive.appdata` (ver sección 4).
5. Estado **En pruebas**: añade tu cuenta (y las de otros testers) en *Usuarios de prueba*. En modo pruebas el permiso caduca a los 7 días; para uso normal hay que pulsar *Publicar app*. `drive.appdata` y `drive.file` no son scopes restringidos, así que no piden la verificación pesada de Google.

## 3. Cliente OAuth de Android

**APIs y servicios > Credenciales > Crear credenciales > ID de cliente de OAuth > Android.**

- Nombre del paquete: `com.metalsyntax.vitreon` (`applicationId` en `app/build.gradle.kts`).
- Huella SHA-1 del certificado con el que se firma el APK. Hay que crear **un cliente por cada firma** (debug y release):

```bash
# debug (el que usa ./gradlew assembleDebug); contraseña android
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1

# o por Gradle (muestra todas las variantes)
~/.gradle/local/gradle-8.2/bin/gradle --offline :app:signingReport

# release: el keystore propio
keytool -list -v -keystore /ruta/release.jks -alias <alias> | grep SHA1
```

Aviso: hoy el build `release` está firmado con la llave **debug** (`signingConfig = signingConfigs.getByName("debug")` en `app/build.gradle.kts`), así que su SHA-1 es el mismo. Si algún día se firma con una llave propia, o si se sube a Play (Play App Signing re-firma: copia el SHA-1 de *Play Console > Integridad de la app*), hay que crear otro cliente Android con ese SHA-1.

No hace falta un client secret ni un archivo `google-services.json`: el cliente Android solo se identifica por paquete + SHA-1. No hay que poner el ID en el código para el flujo de `AuthorizationClient`.

## 4. Scope: `drive.appdata` (recomendado) o `drive.file`

| Scope | Qué ve la app | Para Vitreon |
|---|---|---|
| `drive.appdata` | Solo una carpeta oculta (`appDataFolder`) que ella misma crea; el usuario no la ve en Drive | Mejor para sincronizar el JSON: no se mezcla con sus archivos y no se puede borrar por accidente |
| `drive.file` | Solo archivos creados o abiertos por la app, visibles en Drive | Es el que usan la web (`services/googleDrive.ts`) y Parity. Usarlo si quieres que web y móvil compartan archivos |

Atención: si el móvil usa `appdata` y la web `drive.file`, **no verán el mismo archivo**. Para compartir entre ambos usa `drive.file` en las dos.

## 5. Qué falta en el código

1. `app/build.gradle.kts`: `implementation("com.google.android.gms:play-services-auth:21.0.0")` (la misma versión que Parity).
2. `AndroidManifest.xml`: `<uses-permission android:name="android.permission.INTERNET" />`.
3. Nuevo `app/src/main/java/com/metalsyntax/vitreon/sync/Drive.kt` (copiar de Parity y adaptar):
   - `authorize(activity, onToken, onResolution, onError)` con `Identity.getAuthorizationClient(...).authorize(AuthorizationRequest...)`; si devuelve `hasResolution()`, lanzar el `PendingIntent` y leer el token con `getAuthorizationResultFromIntent`.
   - `silentToken(ctx)` para sincronizar sin pantalla.
   - Llamadas REST con `HttpURLConnection` y `Authorization: Bearer <token>` (no hace falta el SDK de Drive): listar (`GET /drive/v3/files?q=...` con `spaces=appDataFolder` si se usa `appdata`), crear (`POST`), actualizar (`PATCH /upload/drive/v3/files/{id}?uploadType=media`), leer (`?alt=media`).
   - Archivo `vitreon_sync.json` con `Store.exportJson()` (formato web + categorías).
4. Mezcla al bajar: reutilizar `Store.analyze(notes, categories)` + `Store.apply(...)` (los mismos que la importación de archivos Vitreon) en vez de reemplazar todo. Los adjuntos viven cifrados en `filesDir/att/<id>` y **no** van en el JSON; habría que subirlos aparte (un archivo por adjunto) o dejarlos fuera en la primera versión.
5. UI en `ui/Settings.kt`: botones "Conectar Drive", "Sincronizar ahora", "Restaurar desde Drive" y la fecha de la última sincronización (`SharedPreferences`).
6. Opcional: auto-sync como `AutoDrive` de Parity (al abrir bajar, al cambiar subir con 20-30 s de debounce, al pasar a segundo plano subir si hay cambios pendientes) enganchado a `Store.commit` y al ciclo de vida de `MainActivity`.
7. Las notas bloqueadas salen del JSON con su `sealed` cifrado (el PIN nunca se sube); eso ya lo hace `Store.exportJson`.

## 6. Cómo lo hace Parity (`~/Documents/GitHub/parity-kotlin`)

- `app/build.gradle.kts` línea ~42: dependencia `play-services-auth:21.0.0`.
- `app/src/main/java/com/metalsyntax/paritykt/sync/Sync.kt`: `SCOPE = drive.file`, `authorize`, `tokenFrom`, `silentToken`, `driveSync` (líneas ~128-146: lista, sube con PATCH/POST y poda copias viejas), `driveBackups`, `driveRead`, `http()`.
- `.../sync/AutoDrive.kt`: auto-sync con debounce (`onOpen`, `onChange`, `onBackground`).
- `.../ui/More.kt` (~319-380): `rememberDriveToken` / `rememberDriveSync` (lanzador del `PendingIntent`) y el botón "Restaurar desde Drive".

## 7. Probar

1. Instalar el APK firmado con la misma llave cuyo SHA-1 registraste.
2. Cuenta añadida como usuario de prueba. Si sale "Acceso bloqueado" o error 10/`DEVELOPER_ERROR`, el SHA-1 o el nombre de paquete no coinciden con el cliente.
3. Si tras 7 días en modo pruebas deja de pedir token, volver a conectar o publicar la app.
