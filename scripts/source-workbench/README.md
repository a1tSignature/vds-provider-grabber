# source-workbench — как писать `catalog-seed/sources/*.json`

Инструменты для пакетного написания декларативных источников краулера для кандидатов из
`catalog-seed/catalog-dump.sql`. Конфиги проверяются локально, на сохранённых страницах, тем же
кодом, что и в краулере: формат, `DeclarativeAdapter`, `SnapshotValidator`.

Kotlin-часть — `src/test/kotlin/org/a1tsign/vds/crawler/ScratchSourceProbeTest.kt`. Она запускается
только с переменной `PROBE_DIR`, поэтому обычный `./gradlew test` её пропускает.

## Процесс

```bash
W=$(mktemp -d /tmp/vds-selectors.XXXXXX)
B=scripts/source-workbench
source_probe() {
  ./gradlew -PskipFrontend test --rerun --tests "org.a1tsign.vds.crawler.ScratchSourceProbeTest.$1"
}

# 1. Кандидаты с успешным обогащением, у которых ещё нет файла источника
python3 -I $B/candidates.py catalog-seed/catalog-dump.sql catalog-seed/sources $W

# 2. Страницы через загрузчик краулера (robots.txt, редиректы, лимиты) -> $W/pages
PROBE_DIR="$W" source_probe fetch
awk -F '\t' '$1 == "PAGE" { print $2 }' "$W/fetch.log" > "$W/auto.txt"

# 3. Автопоиск карточек тарифов -> $W/auto.out (селектор группы + HTML первой карточки)
PROBE_DIR="$W" source_probe auto

# 4. Точечные пробы -> $W/probe.out. В $W/probe.txt строки `slug|css-селектор`
#    или `slug|@текст` (путь предков элементов, чей собственный текст содержит «текст»).
#    PROBE_N=20 — сколько совпадений печатать (без HTML), PROBE_HTML=1 — вернуть HTML.
PROBE_DIR="$W" source_probe probe

# 5. Спеки -> JSON (формат спека — в шапке gen.py, живой пример — examples/example-spec.py)
python3 -I $B/gen.py $W/out $W/providers.tsv $W/rest.tsv my-spec.py

# 6. Проверка тремя гейтами краулера -> $W/check.out, затем поиск правдоподобных, но неверных чисел
PROBE_DIR="$W" SRC_SUBDIR=out VALIDATE_OUT=check.out source_probe validate
awk '/^## .*\.json: (INVALID|FAIL|SNAPSHOT-INVALID)/ { print; failed=1 } END { exit failed }' "$W/check.out"
python3 -I $B/sanity.py $W/check.out

# 7. Сверить редиректы и цены: в fetch.log видны исходный и конечный URL каждого PAGE
awk -F '\t' '$1 == "SKIP" || ($1 == "PAGE" && $3 != $4)' "$W/fetch.log"
```

Чтобы взять кандидатов из текущей локальной базы, замените входной дамп в шаге 1 на `"$W/live.sql"`.
Эта команда читает только две таблицы каталога.

```bash
docker compose exec -T postgres sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --data-only --no-owner --no-privileges -t public.provider -t public.provider_enrichment' \
  > "$W/live.sql"
python3 -I "$B/candidates.py" "$W/live.sql" catalog-seed/sources "$W"
```

Для партии из 100 источников берите кандидатов из `rest.tsv` и расширяйте выборку через
`all.tsv`, пока не соберёте 100 проверенных файлов. Шаг `fetch` принимает TSV из двух
столбцов (`slug` и URL или `slug~номер` и URL для нескольких страниц). Для каждой принятой страницы
должна быть строка `PAGE`. Проверьте домен после редиректа и сверьте значения всех тарифов с
сохранённым HTML. Скопируйте прошедшие проверку файлы в `catalog-seed/sources/` и добавьте строки
в таблицу `catalog-seed/README.md`. Этот процесс готовит файлы селекторов для отдельного запуска
тарифного краулера.

### Когда страница из discovery — не страница тарифов

Кандидаты с «прямыми» страницами тарифов быстро заканчиваются. Найдите на сохранённой странице
ссылки этого же провайдера на страницы VPS/VDS. Запишите варианты в `variants-input.tsv`, по одной
строке `slug~номер<TAB>URL`. Затем вместо шагов 2–3:

```bash
PROBE_DIR="$W" FETCH_INPUT=variants-input.tsv FETCH_OUTPUT=variants-fetch.log source_probe fetch
awk -F '\t' '$1 == "PAGE" { print $2 "\t" $3 }' "$W/variants-fetch.log" > "$W/variants.tsv"
cut -f1 "$W/variants.tsv" > "$W/auto.txt" && PROBE_DIR="$W" source_probe auto
python3 -I $B/best.py $W                     # лучший вариант -> pages/<slug>.html, rest.tsv, auto.txt
PROBE_DIR="$W" source_probe auto             # карточки уже только по выбранным страницам
```

## Грабли, на которые уже наступали

- `OK` от валидатора подтверждает формат и допустимость чисел, но не их соответствие тарифу.
  `sanity.py` теперь сравнивает разобранную цену с вероятным числом из исходного `PRICE INPUTS`
  и сигналит о расхождениях на порядок и более. Например, `₹3,299.00` → `3 INR` и
  `5 . 74 €` → `74 EUR` будут отмечены. Это эвристика: после неё вручную сверьте исходный текст,
  валюту, период оплаты и правильность выбранного элемента для каждой карточки, особенно в конце списка.
- Для `₹3,299.00` нужен `grouping=","`; для разнесённых по тегам `5 . 74 €` — `grouping=" "`.
  Незаданные разделители приводят к отказу разбора, чтобы цена не превращалась в `3` или `74`.
- Если валюта страницы меняется по локали или cookie, читайте её через `bySymbol`.
  `fixed` подходит для явно подписанного атрибута вроде `data-price-usd`, а не для произвольной цены.
- Проверяйте единицу и контекст цены: пробный период не является `MONTHLY`, а обязательная
  квартальная оплата должна читаться полной суммой как `QUARTERLY`.
- Ограниченный трафик нужно задавать в `trafficLimitGb`, иначе пустое значение отображается как
  безлимитный. В строке `1Gbps - 2TB Bandwidth` объём равен 2000 ГБ, а не скорости порта.
- Не распространяйте `KVM` и наличие первого тарифа на остальные карточки: один блок может
  содержать OpenVZ, KVM и распроданные предложения. Читайте виртуализацию и наличие отдельно.
- `INVALID … grouping must differ from the decimal separator` — при `grouping="."` нужен `decimal=","`.
- Единицы `ГБ`, `Гб`, `Go` парсер не знает: на `ramMb`/`diskGb` ставьте `unit="GB"`, иначе 2 ГБ
  превратятся в 2 МБ (`sanity.py` это ловит).
- `li:contains(IPv4)` + «IPv4: 1» прочитает 4 — берите элемент со значением или `V(1)`.
- `li:contains(…)` совпадает и с самой строкой-карточкой, если она тоже `li` — сужайте до
  `ul.<класс> li:contains(…)`.
- Одинаковые карточки на разных вкладках (локации, ОС) — `SnapshotValidator` отклонит дубли имён:
  сузьте корень до одной вкладки (`:lt(n)`, `div.tab.active`) или возьмите имя вместе с локацией.
  Если тарифы на странице показаны не все — `complete=False`.
- Классы Tailwind с `:`, `/`, `[` в селекторе не работают — цепляйтесь за структуру
  (`div.rounded-2xl:has(h3)`) или за `[class*=…]` (`~=` и `:matches` формат запрещает).
- `virtualization=KVM` ставьте константой, только если слово KVM есть на странице; иначе `UNKNOWN`.
- Цена «за месяц при оплате на 2–3 года» — не `MONTHLY`; ищите помесячную цену в атрибутах
  (`data-monthly`, `data-price`) или пропускайте сайт.
- Страница на другом хосте провайдера (`go.example.com`) — `ack=True`.
- `2,00 GB` разбирается как 200: запятая в числе считается разделителем тысяч. Такие сайты лучше
  пропустить или брать значение из атрибута.
- Число без единицы («16 ddr5») — `unit="GB"`, иначе выйдут мегабайты.
- Без имени тарифа на карточке — `"name": V("…")` плюс `"disambiguateNames": True` (имя дополнится
  диском/RAM); одинаковые по железу строки так не различить — сузьте выборку.
- Одна вёрстка на мобильный и десктоп даёт каждую карточку дважды: корень — первый контейнер
  (`div:has(> div.card)` берётся через `selectFirst`).
- Строки-заглушки, аддоны и «Configure your own» отсекайте в `rowSelector` через `:has(...)`
  с обязательным для тарифа элементом, и ставьте `complete=False`, если часть тарифов не берётся.
