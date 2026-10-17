# InfernoArena — Небесная Арена Инферно

Paper/Spigot 1.20.1, Java 17, нужен WorldEdit (или FAWE).

## Сборка
    mvn clean package
Готовый jar: `target/InfernoArena.jar` → в папку `plugins/`.

## Установка
1. Положите jar в `plugins/`, запустите сервер — создастся `plugins/InfernoArena/` (config.yml + inferno_sky_arena.schem).
2. Старый плагин аирдропов для этой арены отключите (этот плагин делает всё сам).
3. Права: `inferno.admin` (по умолчанию OP) — команды start / stop / reload / tp.

## Команды
| Команда | Кто | Что делает |
|---|---|---|
| /arena (/inferno) | все | тёмная справка + статус; админы дополнительно видят список админ-команд |
| /arena start [here] | admin | запустить сейчас (here — на своём месте) |
| /arena stop | admin | убрать арену |
| /arena reload | admin | перечитать config.yml |
| /arena tp | admin | телепорт на арену |

## Как идёт ивент
18:00 МСК — арена вставляется в небо, шулкер заперт (5 мин) → шулкер открывается → ещё 20 мин → арена исчезает.
Таймеры и время меняются в config.yml (`schedule`, `timers`).
