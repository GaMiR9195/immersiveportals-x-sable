# Сборка на GitHub Actions (0.6.0)

Workflow `.github/workflows/build.yml` собирает патченные нативы Rapier под все ОС и один jar, в котором
`IplNativesOverrideMixin` при запуске сам выбирает библиотеку под текущую ОС и архитектуру.

Нативы собираются так же, как у самого Sable (`sable-main/.github/workflows/build-rust.yml`): все цели
на одном `ubuntu-latest`, в Docker-образах `natives/container/zigbuild` (cargo-zigbuild → Linux, macOS)
и `natives/container/xwin` (cargo-xwin, MSVC → Windows), с nightly из `natives/rust-toolchain.toml`.
Прежний вариант «отдельный раннер на каждую ОС» удалён: Windows- и macOS-раннеры ломались каждый
по-своему (MinGW-линкер, предустановленный софт, очереди и лимиты macOS). К тому же минуты macOS
и Windows в приватных репозиториях стоят в 10 и 2 раза дороже Linux.

## Что делает workflow

| Задача | Что внутри | Результат |
|---|---|---|
| `natives` | `NO_STAGE=1 bash natives/build-cross.sh` (с галочкой `windows_arm64` — `... all`); проверка `file`, что каждая библиотека нужной ОС и архитектуры | артефакт `natives` (хранится 14 дней) |
| `jar` | раскладывает нативы в проект, `bash ./gradlew jarJar`, проверяет, что каждая библиотека попала в jar | артефакт `immersive-portals-x-sable-0.6.0` |
| `release` | только для тегов `v*`: создаёт GitHub Release (если его нет) и прикрепляет jar | файлы в релизе |

Цели по умолчанию: `linux-x86_64`, `linux-aarch64` (glibc 2.17, работает и на старых дистрибутивах),
`macos-x86_64`, `macos-aarch64`, `windows-x86_64`. `windows-aarch64` — по галочке.

Раскладка (та же, что у `build-cross.sh` без `NO_STAGE`): `sable_rapier_x86_64_windows.dll` кладётся в
`src/main/resources/natives_ipl/`, остальные — в корень проекта, откуда `processResources` упаковывает их
в `/natives_ipl/`. DLL нельзя класть в корень: из-за `DuplicatesStrategy.EXCLUDE` её перекрыла бы
копия из ресурсов.

Сводка каждого запуска (вкладка Summary) показывает таблицу нативов (размер, sha256, тип файла),
разложенные файлы и число нативов в каждом jar'е.

## Первая настройка (один раз)

1. Залейте проект в репозиторий GitHub. `build/`, `.gradle/`, `run/`, `natives/target/`, `natives/out/`
   и собранные нативы уже в `.gitignore`.
2. **DLL не коммитьте.** `src/main/resources/natives_ipl/` в `.gitignore` — CI собирает DLL сам. Если
   принудительно добавить её (`git add -f`), то при частичной сборке без Windows в jar попадёт эта,
   возможно устаревшая, DLL. Сводка это покажет, но лучше не допускать.
3. Settings → Actions → General → Actions permissions: «Allow all actions and reusable workflows» или
   разрешите `actions/*` и `gradle/actions/*`.
4. Workflow permissions можно оставить «Read repository contents». Задача `release` сама запрашивает
   `contents: write`. Если организация запрещает повышать права, в `release` будет 403 — тогда включите
   «Read and write permissions».
5. Бит исполнения у `gradlew` не нужен: workflow везде вызывает `bash ./gradlew`. По желанию:
   `git update-index --chmod=+x gradlew && git commit -m "gradlew +x"`.

## Запуск

- **Вручную:** Actions → **Build** → Run workflow → выбрать ветку. Галочки:
  - `windows_arm64` — дополнительно собрать Windows aarch64;
  - `allow_partial` — собрать jar, даже если часть нативов не собралась. На этих платформах мод загрузит
    стоковые нативы Sable: портальная физика будет выключена, остальное работает. В логе будет строка
    `ipl-natives`.
- **Релиз по тегу:**
  ```
  git tag v0.6.0
  git push origin v0.6.0
  ```
  Тег с дефисом (`v0.6.0-beta.1`) публикуется как pre-release. Повторный запуск для того же тега
  перезаливает файлы (`--clobber`), а не создаёт второй релиз.
- **Где взять jar:** страница запуска → Artifacts → `immersive-portals-x-sable-0.6.0` (zip). Внутри два
  одинаковых jar'а: `…-0.6.0.jar` и `…-0.6.0-all.jar` (вывод `jarJar`; вложенных библиотек сейчас нет).
  Ставить любой. Если вложенные библиотеки когда-нибудь появятся, правильным будет `-all`.

Новый запуск на той же ветке отменяет предыдущий (`concurrency`).

Время: первый запуск — примерно 30–60 минут (образы, 5 целей с `lto = "thin"` и `codegen-units = 1`,
первая настройка NeoGradle). Потом быстрее: кэшируются загрузки cargo (`natives/target/docker-cargo`,
ключ — тулчейн + `Cargo.lock`) и Gradle (`setup-gradle`). `target/` не кэшируется намеренно, чтобы между
версиями тулчейна ничего не «протухало».

## То же самое локально

Linux, macOS с Docker Desktop или WSL2:
```
bash natives/build-cross.sh            # все цели по умолчанию; результат сразу разложен в проект
bash natives/build-cross.sh all        # + windows aarch64
bash natives/build-cross.sh linux-x86_64 windows-x86_64
bash ./gradlew jarJar                  # JDK 21 → build/libs/
```
`NO_STAGE=1` — только `natives/out/`, без раскладки. `DOCKER=podman` — другой движок контейнеров.
`IPL_CARGO_CACHE=/путь` — где держать кэш cargo.

Без Docker — по-старому, только под свою платформу: `natives/build-windows.ps1` (Windows) или
`bash natives/build-linux.sh` (Linux, macOS).

## Сменить набор платформ или тулчейн

- Постоянно добавить или убрать цель: `default_targets` в `natives/build-cross.sh` и список `expected`
  в шаге «Stage natives» в `build.yml`.
- Новый nightly: поменяйте `channel` в `natives/rust-toolchain.toml`. Скрипт читает его оттуда, а тег
  образов (`ipl-sable-zigbuild:<toolchain>`) меняется сам. Базовые образы закреплены по digest в
  `natives/container/*/Dockerfile`, как у Sable.

## Неполадки

| Симптом | Что делать |
|---|---|
| Падает `docker build` (pull, `toomanyrequests`, сеть) или `cargo xwin cache xwin` | Временное. Перезапустите (Re-run failed jobs). |
| `toolchain '…' is not installed` / rustup не скачал nightly | Перезапустите. При смене nightly проверьте, что он существует и с ним собирается `simd-nightly`. |
| Не собралась одна цель (видно по `FAILED: <цель>` в логе) | Остальные всё равно соберутся и попадут в артефакт `natives`. Чтобы получить jar сейчас — запуск с `allow_partial`; ошибку цели смотрите в логе выше строки `FAILED`. |
| Ошибка линковки macOS (zig) | Обычно это новая зависимость с системными фреймворками. Временно — `allow_partial`, а dylib собрать на Mac через `bash natives/build-linux.sh`, положить в корень и собрать jar локально. |
| `unexpected binary type` в «Check natives» | В `natives/out` попал файл не той ОС или архитектуры. Проверьте `spec()` в `build-cross.sh`. |
| `No space left on device` | Перед сборкой нативов добавьте шаг `sudo rm -rf /usr/share/dotnet /usr/local/lib/android /opt/ghc`. |
| Задача `jar` пропущена (skipped) | Задача `natives` упала, а `allow_partial` не стоит. |
| `missing natives: …` в «Stage natives» | То же: исправьте цель или запускайте с `allow_partial`. |
| `… lacks natives_ipl/…` в «Verify jar contents» | Изменили `processResources` в `build.gradle`? Он должен брать `sable_rapier_*` из корня проекта. |
| Gradle падает с OutOfMemory | `org.gradle.jvmargs` в `gradle.properties` (сейчас `-Xmx6G`; на раннере 16 ГБ). |
| `release`: 403 / `Resource not accessible by integration` | См. пункт 4 настройки. |
