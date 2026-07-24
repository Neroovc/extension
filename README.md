# Mihon Booru Extensions

Extensiones scraper para Mihon que permiten navegar y ver imágenes de **Danbooru**, **Gelbooru**, **Konachan**, **Rule34** y **Yande.re**.

## Cómo instalar en Mihon

1. Abrí Mihon → **Explorar** → → **Repositorio de extensiones**
2. Tocá el `+` y agregá esta URL:
   ```
   https://raw.githubusercontent.com/tuusuario/mihon-extensions/main/index.json
   ```
3. Te deberían aparecer las 5 extensiones para instalar

> **Nota:** Primero tenés que hostear el repo en GitHub. Abajo te explico cómo.

## Cómo compilar

Necesitás **Android Studio** o **JDK 11+** con **Android SDK** (en PC, no en el teléfono).

### Opción 1: Compilar local

```bash
git clone https://github.com/tuusuario/mihon-extensions.git
cd mihon-extensions
./gradlew :src:all:danbooru:assembleRelease
# Los APKs quedan en src/all/danbooru/build/outputs/apk/release/
```

### Opción 2: Con GitHub Actions (automático)

1. Subí el repo a GitHub
2. Creá `.github/workflows/build.yml`:

```yaml
name: Build
on:
  push:
    branches: [main]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - uses: android-actions/setup-android@v3
      - run: ./gradlew assembleRelease
      - uses: actions/upload-artifact@v4
        with:
          name: apks
          path: "*/build/outputs/apk/release/*.apk"
```

## Estructura

```
mihon-extensions/
├── build.gradle.kts            # Root build
├── settings.gradle.kts         # Registro de módulos
├── gradle.properties
├── index.json                  # Repositorio de extensiones
└── src/all/
    ├── danbooru/               # Danbooru scraper
    ├── gelbooru/               # Gelbooru scraper
    ├── konachan/               # Konachan scraper
    ├── rule34/                 # Rule34 scraper
    └── yandere/                # Yande.re scraper
```

## Agregar más fuentes

Cada extensión es scraper puro (JSoup). Si querés agregar otra fuente:

1. Copiá una carpeta existente (ej. `konachan`)
2. Cambiá `baseUrl`, `name`, y ajustá los selectores JSoup
3. Agregá el módulo en `settings.gradle.kts`
4. Agregá la entrada en `index.json`

## Licencia

Apache 2.0
